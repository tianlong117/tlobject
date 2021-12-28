package cn.tianlong.java.demo.base;


import cn.tianlong.tlobject.base.TLBaseModule;
import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;

public class Light extends TLBaseModule {
	private int i =0;
	public Light (String name ){
		super(name);
	}
	public Light (String name,TLObjectFactory moduleFactory)  {
		super(name,moduleFactory);
	}

	@Override
	protected TLBaseModule init() {
		System.out.println("---模块创建: "+name + " 创建 ");
		return this ;
	}

	@Override
	protected TLMsg checkMsgAction(Object fromWho, TLMsg msg) {
		
		switch (msg.getAction()){
		case "on" : 
			on( fromWho ,msg) ;
			break ;
		case "off" :
			off(fromWho ,msg); 
			break ;
		default : System.out.println("no action");
		}
		return null;
	}

	private void on (Object fromWho, TLMsg msg) {
		System.out.println("灯 打开 " );
		putMsg("house",new TLMsg("light"));
	}
	private void off(Object fromWho, TLMsg msg ) {
		System.out.println("灯关上 " );
		putMsg("house",new TLMsg("dark"));
	}

}
