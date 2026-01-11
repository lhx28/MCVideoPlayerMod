package com.example.videoPlayerPlugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 视频播放器服务端插件（Bukkit/Spigot）
 * 核心修改：1. 对齐B站音乐插件经验，简化数据包写入，严格匹配客户端内置编解码
 * 2. 允许非玩家（控制台/命令方块）执行：单一玩家命令（指定玩家名）+ 广播命令（all参数）
 * 3. 玩家执行逻辑保持不变，兼容旧用法
 */
public class VideoPlayerPlugin extends JavaPlugin implements TabCompleter {
    // 与客户端保持一致的插件消息通道ID
    private static final String CHANNEL = "videoplayer:main";
    // 同步客户端支持的格式
    private static final List<String> SUPPORTED_FORMATS = Arrays.asList("m3u8", "flv", "default");
    // 命令关键词（用于Tab补全）
    private static final List<String> COMMAND_KEYWORDS = Arrays.asList("stop", "all");

    @Override
    public void onEnable() {
        // 注册插件消息输出通道
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);

        // 注册命令和对应的Tab补全器
        getCommand("videoplayer").setExecutor(this);
        getCommand("videoplayer").setTabCompleter(this);
        getCommand("vplay").setExecutor(this);
        getCommand("vplay").setTabCompleter(this);
        getCommand("vstop").setExecutor(this);
        getCommand("vstop").setTabCompleter(this);

        getLogger().info("========== 视频播放器服务端插件已启用 ==========");
        getLogger().info("通道已注册：" + CHANNEL);
        getLogger().info("使用 /vplay 查看帮助信息，支持Tab补全");
        getLogger().info("非玩家（控制台/命令方块）支持：");
        getLogger().info("  - 单一玩家：/vplay <目标玩家名> <链接> <格式> <坐标x6> <旋转角>");
        getLogger().info("  - 广播所有：/vplay <链接> <格式> <坐标x6> <旋转角> all");
    }

    @Override
    public void onDisable() {
        // 注销插件消息通道
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getLogger().info("视频播放器服务端插件已禁用");
    }

    /**
     * 命令处理核心方法
     * 关键修改：非玩家支持两种命令格式，自动识别单一玩家/广播
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 权限校验（所有执行者均需权限，包括控制台/命令方块）
        if (!sender.hasPermission("permission: videoplayer.command")) {
            sender.sendMessage("§c命令权限不足！");
            return true;
        }

        // 拆分执行者类型：玩家/非玩家（控制台、命令方块等）
        Player commandPlayer = (sender instanceof Player) ? (Player) sender : null;
        boolean isNonPlayer = (commandPlayer == null);

        String cmdName = command.getName().toLowerCase();

        // 处理单独的停止命令 /vstop（非玩家支持：/vstop <目标玩家名> 或 /vstop all）
        if (cmdName.equals("vstop")) {
            handleStopCommand(sender, commandPlayer, args, isNonPlayer);
            return true;
        }

        // 处理播放相关命令（/videoplayer /vplay）
        if (cmdName.equals("videoplayer") || cmdName.equals("vplay")) {
            if (args.length == 0) {
                sendHelpMessage(sender, isNonPlayer); // 适配非玩家的帮助信息
                return true;
            }

            // 处理停止子命令（/vplay stop <目标玩家名> 或 /vplay stop all）
            if (args[0].equalsIgnoreCase("stop")) {
                handleStopCommand(sender, commandPlayer, args, isNonPlayer);
                return true;
            }

            // 处理播放命令（自动识别玩家/非玩家格式）
            handlePlayCommand(sender, commandPlayer, args, isNonPlayer);
            return true;
        }

        return false;
    }

    /**
     * 处理播放命令，自动适配玩家/非玩家格式
     * 关键修改：
     * - 玩家：原有格式（/vplay <链接> <格式> <x6> <旋转角> [all]）
     * - 非玩家：两种格式（单一玩家/广播），自动识别
     */
    private void handlePlayCommand(CommandSender sender, Player commandPlayer, String[] args, boolean isNonPlayer) {
        try {
            // 1. 区分命令格式，提取核心参数
            PlayCommandParams params = parsePlayParams(sender, args, isNonPlayer);
            if (params == null) return; // 解析失败已提示，直接返回

            // 2. 格式校验
            if (!SUPPORTED_FORMATS.contains(params.videoFormat)) {
                sender.sendMessage("§e不支持的视频格式，将使用 default 自动探测");
                params.videoFormat = "default";
            }

            // 3. 坐标范围校验（原有逻辑保留）
            if (Math.abs(params.endX - params.startX) > 5 ||
                    Math.abs(params.endY - params.startY) > 5 ||
                    Math.abs(params.endZ - params.startZ) > 5) {
                sender.sendMessage("§e坐标差值不能超过5！请调整参数");
                return;
            }

            // 4. 发送数据包
            if (params.broadcastToAll) {
                // 广播命令：发送给所有在线玩家
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    sendVideoPlayData(onlinePlayer, params.videoUrl, params.videoFormat,
                            params.startX, params.startY, params.startZ,
                            params.endX, params.endY, params.endZ, params.yaw);
                }
                sender.sendMessage("§a已向所有在线玩家发送视频播放指令：" + params.videoUrl);
                getLogger().info((isNonPlayer ? "非玩家" : "玩家 " + commandPlayer.getName()) +
                        " 广播视频播放：" + params.videoUrl + "（旋转角：" + params.yaw + "°）");
            } else {
                // 单一玩家命令：发送给目标玩家
                if (params.targetPlayer == null) {
                    sender.sendMessage("§c目标玩家不在线或不存在！");
                    return;
                }
                sendVideoPlayData(params.targetPlayer, params.videoUrl, params.videoFormat,
                        params.startX, params.startY, params.startZ,
                        params.endX, params.endY, params.endZ, params.yaw);
                sender.sendMessage("§a已向玩家 " + params.targetPlayer.getName() + " 发送视频播放指令：" + params.videoUrl);
                getLogger().info((isNonPlayer ? "非玩家" : "玩家 " + commandPlayer.getName()) +
                        " 向 " + params.targetPlayer.getName() + " 发送视频播放：" + params.videoUrl + "（旋转角：" + params.yaw + "°）");
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("§c坐标或旋转角格式错误！请输入有效的数字（支持小数）");
        } catch (Exception e) {
            sender.sendMessage("§c发送视频播放指令失败：" + e.getMessage());
            getLogger().severe("处理播放命令异常：" + e.getMessage());
        }
    }

    /**
     * 解析播放命令参数，自动适配玩家/非玩家格式
     */
    private PlayCommandParams parsePlayParams(CommandSender sender, String[] args, boolean isNonPlayer) {
        PlayCommandParams params = new PlayCommandParams();

        if (isNonPlayer) {
            // 非玩家格式：两种情况自动识别
            if (args.length == 10 && args[9].equalsIgnoreCase("all")) {
                // 格式1：广播命令 → /vplay <链接> <格式> <x1> <y1> <z1> <x2> <y2> <z2> <旋转角> all
                params.broadcastToAll = true;
                params.videoUrl = args[0];
                params.videoFormat = args[1].toLowerCase();
                params.startX = Double.parseDouble(args[2]);
                params.startY = Double.parseDouble(args[3]);
                params.startZ = Double.parseDouble(args[4]);
                params.endX = Double.parseDouble(args[5]);
                params.endY = Double.parseDouble(args[6]);
                params.endZ = Double.parseDouble(args[7]);
                params.yaw = Float.parseFloat(args[8]);
            } else if (args.length == 10) {
                // 格式2：单一玩家命令 → /vplay <目标玩家名> <链接> <格式> <x1> <y1> <z1> <x2> <y2> <z2> <旋转角>
                params.broadcastToAll = false;
                String targetPlayerName = args[0];
                params.targetPlayer = Bukkit.getPlayerExact(targetPlayerName); // 精准匹配玩家名
                params.videoUrl = args[1];
                params.videoFormat = args[2].toLowerCase();
                params.startX = Double.parseDouble(args[3]);
                params.startY = Double.parseDouble(args[4]);
                params.startZ = Double.parseDouble(args[5]);
                params.endX = Double.parseDouble(args[6]);
                params.endY = Double.parseDouble(args[7]);
                params.endZ = Double.parseDouble(args[8]);
                params.yaw = Float.parseFloat(args[9]);
            } else {
                // 参数长度错误，提示格式
                sender.sendMessage("§c非玩家命令格式错误！支持两种格式：");
                sender.sendMessage("§c1. 单一玩家：/vplay <目标玩家名> <链接> <格式> <x1> <y1> <z1> <x2> <y2> <z2> <旋转角>");
                sender.sendMessage("§c2. 广播所有：/vplay <链接> <格式> <x1> <y1> <z1> <x2> <y2> <z2> <旋转角> all");
                return null;
            }
        } else {
            // 玩家格式（原有逻辑保留）
            if (args.length < 9) {
                sender.sendMessage("§c参数不足！使用 /vplay 查看帮助");
                return null;
            }
            params.broadcastToAll = args.length >= 10 && args[9].equalsIgnoreCase("all");
            params.targetPlayer = (Player) sender; // 目标玩家为自己
            params.videoUrl = args[0];
            params.videoFormat = args[1].toLowerCase();
            params.startX = Double.parseDouble(args[2]);
            params.startY = Double.parseDouble(args[3]);
            params.startZ = Double.parseDouble(args[4]);
            params.endX = Double.parseDouble(args[5]);
            params.endY = Double.parseDouble(args[6]);
            params.endZ = Double.parseDouble(args[7]);
            params.yaw = Float.parseFloat(args[8]);

            // 旋转角范围校验（0~360）
            if (params.yaw < 0 || params.yaw > 360) {
                sender.sendMessage("§e旋转角超出合理范围（0~360），自动修正为 90.0°");
                params.yaw = 90.0f;
            }
        }

        return params;
    }

    /**
     * 处理停止命令（支持玩家/非玩家，单一玩家/广播）
     * 关键修改：非玩家可执行 /vstop <目标玩家名> 或 /vstop all
     */
    private void handleStopCommand(CommandSender sender, Player commandPlayer, String[] args, boolean isNonPlayer) {
        try {
            if (isNonPlayer) {
                // 非玩家停止命令：两种格式
                if (args.length == 1 && args[0].equalsIgnoreCase("all")) {
                    // 格式1：/vstop all → 停止所有玩家
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        sendVideoStopData(onlinePlayer);
                    }
                    sender.sendMessage("§a已向所有在线玩家发送视频停止指令");
                    getLogger().info("非玩家 广播视频停止指令");
                } else if (args.length == 1) {
                    // 格式2：/vstop <目标玩家名> → 停止单个玩家
                    String targetPlayerName = args[0];
                    Player targetPlayer = Bukkit.getPlayerExact(targetPlayerName);
                    if (targetPlayer != null && targetPlayer.isOnline()) {
                        sendVideoStopData(targetPlayer);
                        sender.sendMessage("§a已向玩家 " + targetPlayerName + " 发送视频停止指令");
                        getLogger().info("非玩家 向 " + targetPlayerName + " 发送视频停止指令");
                    } else {
                        sender.sendMessage("§c目标玩家不在线或不存在！");
                    }
                } else {
                    sender.sendMessage("§c非玩家停止命令格式错误！支持：/vstop <目标玩家名> 或 /vstop all");
                }
            } else {
                // 玩家停止命令（原有逻辑保留）
                boolean stopAll = args.length >= 1 && args[0].equalsIgnoreCase("all");
                if (stopAll) {
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        sendVideoStopData(onlinePlayer);
                    }
                    sender.sendMessage("§a已向所有在线玩家发送视频停止指令");
                    getLogger().info("玩家 " + commandPlayer.getName() + " 广播视频停止指令");
                } else {
                    sendVideoStopData(commandPlayer);
                    sender.sendMessage("§a已发送视频停止指令");
                    getLogger().info("向玩家 " + commandPlayer.getName() + " 发送视频停止指令");
                }
            }
        } catch (Exception e) {
            sender.sendMessage("§c发送视频停止指令失败：" + e.getMessage());
            getLogger().severe("处理停止命令异常：" + e.getMessage());
        }
    }

    /**
     * 发送视频播放数据包（原有逻辑保留）
     */
    private void sendVideoPlayData(Player player, String videoUrl, String videoFormat,
                                   double startX, double startY, double startZ,
                                   double endX, double endY, double endZ,
                                   float yaw) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(0); // 命令类型：0=播放
            writeMCString(dos, videoUrl); // 视频链接
            writeMCString(dos, videoFormat); // 视频格式
            // 6个坐标（大端序double）
            dos.writeDouble(startX);
            dos.writeDouble(startY);
            dos.writeDouble(startZ);
            dos.writeDouble(endX);
            dos.writeDouble(endY);
            dos.writeDouble(endZ);
            dos.writeDouble(yaw); // 旋转角

            byte[] packetData = baos.toByteArray();
            player.sendPluginMessage(this, CHANNEL, packetData);
            getLogger().info("向玩家 " + player.getName() + " 发送播放数据包，大小：" + packetData.length + " 字节");

        } catch (IOException e) {
            getLogger().severe("发送视频播放数据包失败（玩家：" + player.getName() + "）：" + e.getMessage());
        }
    }

    /**
     * 发送视频停止数据包（原有逻辑保留）
     */
    private void sendVideoStopData(Player player) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(1); // 命令类型：1=停止
            writeMCString(dos, "");
            writeMCString(dos, "");
            // 默认坐标（避免客户端解码异常）
            dos.writeDouble(0.0);
            dos.writeDouble(0.0);
            dos.writeDouble(0.0);
            dos.writeDouble(0.0);
            dos.writeDouble(0.0);
            dos.writeDouble(0.0);
            dos.writeDouble(0.0); // 默认旋转角

            byte[] packetData = baos.toByteArray();
            player.sendPluginMessage(this, CHANNEL, packetData);
            getLogger().info("向玩家 " + player.getName() + " 发送停止数据包，大小：" + packetData.length + " 字节");

        } catch (IOException e) {
            getLogger().severe("发送视频停止数据包失败（玩家：" + player.getName() + "）：" + e.getMessage());
        }
    }

    /**
     * 写入MC标准字符串（原有逻辑保留）
     */
    private void writeMCString(DataOutputStream dos, String str) throws IOException {
        byte[] strBytes = str.getBytes(StandardCharsets.UTF_8);
        int length = strBytes.length;

        // 标准MC VarInt编码
        int shift = 0;
        while ((length & -128) != 0) {
            if (shift > 35) {
                throw new IOException("VarInt长度超过最大限制（35位）");
            }
            dos.writeByte((length & 127) | 128);
            length >>>= 7;
            shift += 7;
        }
        dos.writeByte(length);
        dos.write(strBytes);
    }

    /**
     * 发送帮助信息（适配非玩家格式）
     */
    private void sendHelpMessage(CommandSender sender, boolean isNonPlayer) {
        sender.sendMessage("§6========== 视频播放器插件帮助 ==========");
        if (isNonPlayer) {
            // 非玩家帮助信息
            sender.sendMessage("§e非玩家（控制台/命令方块）支持格式：");
            sender.sendMessage("§e1. 单一玩家：/vplay <目标玩家名> <链接> <格式> <x1> <y1> <z1> <x2> <y2> <z2> <旋转角>");
            sender.sendMessage("§e2. 广播所有：/vplay <链接> <格式> <x1> <y1> <z1> <x2> <y2> <z2> <旋转角> all");
            sender.sendMessage("§e3. 停止单一玩家：/vstop <目标玩家名>");
            sender.sendMessage("§e4. 停止所有玩家：/vstop all");
        } else {
            // 玩家帮助信息（原有逻辑保留）
            sender.sendMessage("§e/vplay <视频链接> <格式> <startX> <startY> <startZ> <endX> <endY> <endZ> <旋转角> [all]");
            sender.sendMessage("§e/vstop [all] §7- 停止视频播放");
            sender.sendMessage("§e/videoplayer stop [all] §7- 停止视频播放");
        }
        sender.sendMessage("§7支持格式：m3u8 / flv / default（自动探测）");
        sender.sendMessage("§7参数说明：");
        sender.sendMessage("§7  - 坐标：起始3个坐标 + 结束3个坐标（支持小数，差值≤5）");
        sender.sendMessage("§7  - 旋转角：TV水平旋转角度（0~360，例：90.0、45.5）");
        sender.sendMessage("§7  - all：可选，广播给所有在线玩家");
        sender.sendMessage("§7示例（非玩家）：/vplay lhx28 http://xxx.m3u8 m3u8 10 10 10 10 20 20 90.0");
        sender.sendMessage("§7示例（玩家）：/vplay http://xxx.flv flv 5 5 5 5 10 10 45.0 all");
        sender.sendMessage("§7注意：需要安装配套Fabric客户端模组才能播放");
    }

    /**
     * Tab补全核心方法（适配非玩家格式，补全玩家名）
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean isNonPlayer = !(sender instanceof Player);
        String cmdName = command.getName().toLowerCase();
        List<String> completions = new ArrayList<>();

        // 处理 /vstop 补全
        if (cmdName.equals("vstop")) {
            if (args.length == 1) {
                // 补全：all + 在线玩家名
                addMatchingCompletions(completions, "all", args[0]);
                addOnlinePlayersCompletions(completions, args[0]);
            }
            return completions;
        }

        // 处理 /vplay /videoplayer 补全
        if (cmdName.equals("vplay") || cmdName.equals("videoplayer")) {
            switch (args.length) {
                case 1:
                    if (isNonPlayer) {
                        // 非玩家第1个参数：补全在线玩家名（单一玩家）或 all（广播）
                        addMatchingCompletions(completions, "all", args[0]);
                        addOnlinePlayersCompletions(completions, args[0]);
                    } else {
                        // 玩家第1个参数：补全 stop 或 视频链接（此处仅补全stop关键词）
                        addMatchingCompletions(completions, "stop", args[0]);
                    }
                    break;
                case 2:
                    if (isNonPlayer) {
                        // 非玩家第2个参数：如果第1个是玩家名 → 补全视频格式；如果第1个是all → 无补全（all是广播格式的最后一个参数）
                        if (!args[0].equalsIgnoreCase("all")) {
                            addMatchingCompletions(completions, SUPPORTED_FORMATS, args[1]);
                        }
                    } else {
                        // 玩家第2个参数：如果第1个是stop → 补全all；否则补全视频格式
                        if (args[0].equalsIgnoreCase("stop")) {
                            addMatchingCompletions(completions, "all", args[1]);
                        } else {
                            addMatchingCompletions(completions, SUPPORTED_FORMATS, args[1]);
                        }
                    }
                    break;
                case 3, 4, 5, 6, 7, 8:
                    // 坐标补全（非玩家：第3-8位是坐标；玩家：第2-7位是坐标 → 统一用玩家当前坐标补全）
                    Player player = sender instanceof Player ? (Player) sender : null;
                    String currentCoord = getPlayerCoord(player, args.length - (isNonPlayer ? 3 : 2));
                    addMatchingCompletions(completions, currentCoord, args[args.length - 1]);
                    break;
                case 9:
                    if (isNonPlayer) {
                        // 非玩家第9位：如果是单一玩家格式 → 旋转角（补全90.0）；如果是广播格式 → 无（第9位是旋转角，第10位是all）
                        if (!args[0].equalsIgnoreCase("all")) {
                            addMatchingCompletions(completions, "90.0", args[8]);
                        }
                    } else {
                        // 玩家第9位：旋转角（补全90.0）
                        addMatchingCompletions(completions, "90.0", args[8]);
                    }
                    break;
                case 10:
                    if (isNonPlayer) {
                        // 非玩家第10位：如果是广播格式 → 补全all
                        if (!args[0].equalsIgnoreCase("all")) {
                            addMatchingCompletions(completions, "all", args[9]);
                        }
                    } else {
                        // 玩家第10位：补全all
                        addMatchingCompletions(completions, "all", args[9]);
                    }
                    break;
                default:
                    break;
            }
            return completions;
        }

        return new ArrayList<>();
    }

    /**
     * 补全在线玩家名（用于非玩家命令的目标玩家选择）
     */
    private void addOnlinePlayersCompletions(List<String> completions, String input) {
        String lowerInput = input.toLowerCase();
        // 获取所有在线玩家名，筛选匹配前缀的
        List<String> onlinePlayerNames = Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(lowerInput))
                .collect(Collectors.toList());
        completions.addAll(onlinePlayerNames);
    }

    /**
     * 筛选匹配输入前缀的补全项（单个）
     */
    private void addMatchingCompletions(List<String> completions, String candidate, String input) {
        if (candidate.toLowerCase().startsWith(input.toLowerCase())) {
            completions.add(candidate);
        }
    }

    /**
     * 筛选匹配输入前缀的补全项（列表）
     */
    private void addMatchingCompletions(List<String> completions, List<String> candidates, String input) {
        for (String candidate : candidates) {
            if (candidate.toLowerCase().startsWith(input.toLowerCase())) {
                completions.add(candidate);
            }
        }
    }

    /**
     * 获取玩家当前坐标的对应值（原有逻辑保留）
     */
    private String getPlayerCoord(Player player, int coordIndex) {
        if (player == null) return "0.0"; // 非玩家时返回默认值
        double x = player.getLocation().getX();
        double y = player.getLocation().getY();
        double z = player.getLocation().getZ();

        return switch (coordIndex) {
            case 0 -> String.format("%.1f", x);
            case 1 -> String.format("%.1f", y);
            case 2 -> String.format("%.1f", z);
            case 3 -> String.format("%.1f", x + 5.0);
            case 4 -> String.format("%.1f", y + 5.0);
            case 5 -> String.format("%.1f", z + 0.0);
            default -> "0.0";
        };
    }

    /**
     * 播放命令参数封装类（简化参数传递）
     */
    private static class PlayCommandParams {
        boolean broadcastToAll; // 是否广播
        Player targetPlayer; // 目标玩家（单一玩家命令时有效）
        String videoUrl; // 视频链接
        String videoFormat; // 视频格式
        double startX, startY, startZ; // 起始坐标
        double endX, endY, endZ; // 结束坐标
        float yaw; // 旋转角
    }
}
