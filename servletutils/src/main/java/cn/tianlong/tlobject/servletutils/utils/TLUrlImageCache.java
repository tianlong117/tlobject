package cn.tianlong.tlobject.servletutils.utils;


import cn.tianlong.tlobject.base.TLMsg;
import cn.tianlong.tlobject.base.TLObjectFactory;
import cn.tianlong.tlobject.modules.LogLevel;
import net.coobird.thumbnailator.Thumbnails;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.HashMap;

import static cn.tianlong.tlobject.cache.TLParamString.CACHE_P_CACHENAME;
import static cn.tianlong.tlobject.servletutils.utils.TLParamString.*;

public class TLUrlImageCache extends TLUrlFileCache {


    public TLUrlImageCache() {
        super();
    }

    public TLUrlImageCache(String name) {
        super(name);
    }

    public TLUrlImageCache(String name, TLObjectFactory modulefactory) {
        super(name, modulefactory);
    }


    protected String afterSaveFile(String cacheFileName, TLMsg msg) {
        int height;
        int width;
        if(msg.isNull(URLIMAGE_P_HEIGHT) && msg.isNull(URLIMAGE_P_WIDTH))
        {
            String cacheName = (String) msg.getParam(CACHE_P_CACHENAME);
            if(cacheTables !=null)
            {
                HashMap<String, String> dataHash=cacheTables.get(cacheName);
                if(dataHash!=null)
                {
                    if(dataHash.get(URLIMAGE_P_HEIGHT)!= null && dataHash.get(URLIMAGE_P_WIDTH)!= null)
                    {
                        height= Integer.parseInt(dataHash.get(URLIMAGE_P_HEIGHT));
                        width=  Integer.parseInt(dataHash.get(URLIMAGE_P_WIDTH));
                    }
                    else
                        return cacheFileName;
                }
                else
                    return cacheFileName;
            }
            else
                return cacheFileName;
        }
        else {
            height=  msg.getIntParam(URLIMAGE_P_HEIGHT,0);
            width= msg.getIntParam(URLIMAGE_P_WIDTH,0);
        }
        if(height==0 || width ==0)
            return cacheFileName ;
        float quality =1.0F ;
        if(!msg.isNull(URLIMAGE_P_QUALITY))
            quality= (float) msg.getParam(URLIMAGE_P_QUALITY);
        String outputFile =msg.getStringParam(URLIMAGE_P_OUTFILE,cacheFileName);
        return compressPic(cacheFileName,outputFile,height,width,quality) ;
    }
    public String compressPic(String inputFile, String outputFile, int height,int width, float quality) {
        if(inputFile ==null || inputFile.isEmpty())
            return null ;
        File input = new File(inputFile);
        if(!input.exists())
            return null ;
        try {
            Thumbnails.Builder<File> fileBuilder = Thumbnails.of(input).scale(1.0).outputQuality(1.0);
            BufferedImage src = fileBuilder.asBufferedImage();
            if(src.getHeight(null) >height || src.getWidth(null) > width) {
                Thumbnails.Builder<File> builder = Thumbnails.of(input);
                builder.size(height, width); //取最大的尺寸变成size，然后等比缩放
                builder.outputQuality(quality).toFile(outputFile);
            } else if(quality <1.0){
                Thumbnails.of(input).scale(1.0).outputQuality(quality).toFile(outputFile);
            }
            return outputFile;
        } catch (IOException e) {
          putLog("compress image is error:"+inputFile,LogLevel.ERROR,"compressPic");
        }
        return outputFile;
    }
}