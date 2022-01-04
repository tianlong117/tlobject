--base目录
1 startup 、sonstartup 演示消息对象运行模式
   startup演示内容：
   小明的妻子在睡觉，小明进屋打开了灯。灯亮了吵醒了妻子。妻子让小明关灯，她在睡觉呢。
   小明没有关灯继续上网。
   sonstartup演示内容：儿子学习。
2 startupaddsonstarup 演示startup将sonstartup加入作为一个模块的运行模式。
  同时演示在主程序startup中发送消息给模块程序
--service目录
  --client目录
    1 cominStartup
       演示base目录startup同样场景。不同的是小明位于客户端，妻子位于服务端。
      演示如何通过简单配置拆分模块到不同的运行机器中。
      先启动server目录下的server，然后运行客户端。
    2 clientPutFileStartup
    演示客户端和服务端互相发送文件。
    更改server目录下 wife_config.xml 配置项<ifputFile value="true"/>,则开启当client登录后，服务端主动发送文件。