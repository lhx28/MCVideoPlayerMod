# MCVideoPlayerMod  
This is a video player mod using malilib for 3d rendering and ffmpeg to decode the video.  
The player is for minecraft 1.21.8-fabric and it depends on the server's packet to work. And an example of the server plugin source code is on the root directory “VideoPlayerPlugin.java”  
The player now have many problems as it often stops when playing m3u8 and will crash the game if the playbox big enough. But sadly, I can't find any way to solve these problems.  
The player will just be used during spring festival 2026 for "Minecraft巧克力FU", so I didn't pay much attention on it's stability and safety.  
### pay attention:  
Please mention that if you let player to use the command like vplay(to play video), the player can make a trick(like dnslog) that get other's who installed the mod's IPs.  

## Chinese language（请以中文为准）  
这是一个用malilib来处理3d渲染以及ffmpeg处理解码/下载的视频播放器  
这个播放器适用于1.21.8的fabric版本并且用服务器包激活（你可以修改），服务器插件的代码在根目录下的“VideoPlayerPlugin.java”里面。  
现在mod正处于开发但是不想继续的阶段，现在有以下问题：  
1.m3u8播放会卡顿（主要是音频帧没入列导致的）  
2.如果视频窗口过大，会导致buffer溢出炸掉mc客户端  
以上两个bug虽然做过尝试性修复但是最终以失败告终，如果你有方案，欢迎来pr.  
这个播放器主要是用于2026年春节“Minecraft巧克力FU”的视频播放，所以我并没有认真的去做视频播放器的稳定性和安全性。  
### 注意（安全漏洞）  
如果您想要允许玩家直接执行播放的命令，小心玩家使用特殊的连接（例如dnslog）来获取服务器上所有安装这个mod的玩家的ip地址。  
