package cn.tianlong.tlobject.modules;


import cn.tianlong.tlobject.base.TLObjectFactory;


public class TLReUsedSingleThreadPoolGroup extends TLReUsedModulePool {

    public TLReUsedSingleThreadPoolGroup(){
        super();
    }
    public TLReUsedSingleThreadPoolGroup(String name ){
        super(name);
    }
    public TLReUsedSingleThreadPoolGroup(String name , TLObjectFactory modulefactory){
        super(name,modulefactory);
    }

    @Override
    protected void setModuleParams(){
        super.setModuleParams();
        modueInPool ="singleThreadPool";
    }

}
