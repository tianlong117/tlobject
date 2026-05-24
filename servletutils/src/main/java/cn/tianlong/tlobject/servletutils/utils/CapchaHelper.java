package cn.tianlong.tlobject.servletutils.utils;

import com.octo.captcha.CaptchaFactory;
import com.octo.captcha.component.image.backgroundgenerator.BackgroundGenerator;
import com.octo.captcha.component.image.backgroundgenerator.FileReaderRandomBackgroundGenerator;
import com.octo.captcha.component.image.backgroundgenerator.UniColorBackgroundGenerator;
import com.octo.captcha.component.image.color.RandomListColorGenerator;
import com.octo.captcha.component.image.color.RandomRangeColorGenerator;
import com.octo.captcha.component.image.fontgenerator.FontGenerator;
import com.octo.captcha.component.image.fontgenerator.RandomFontGenerator;
import com.octo.captcha.component.image.textpaster.DecoratedRandomTextPaster;
import com.octo.captcha.component.image.textpaster.RandomTextPaster;
import com.octo.captcha.component.image.textpaster.TextPaster;
import com.octo.captcha.component.image.textpaster.textdecorator.TextDecorator;
import com.octo.captcha.component.image.wordtoimage.ComposedWordToImage;
import com.octo.captcha.component.image.wordtoimage.WordToImage;
import com.octo.captcha.component.word.FileDictionary;
import com.octo.captcha.component.word.wordgenerator.ComposeDictionaryWordGenerator;
import com.octo.captcha.component.word.wordgenerator.RandomWordGenerator;
import com.octo.captcha.component.word.wordgenerator.WordGenerator;
import com.octo.captcha.engine.CaptchaEngine;
import com.octo.captcha.engine.GenericCaptchaEngine;
import com.octo.captcha.engine.image.ListImageCaptchaEngine;
import com.octo.captcha.image.gimpy.GimpyFactory;
import com.octo.captcha.service.image.ImageCaptchaService;
import com.octo.captcha.service.multitype.GenericManageableCaptchaService;
//import com.sun.image.codec.jpeg.JPEGCodec;
//import com.sun.image.codec.jpeg.JPEGImageEncoder;

import javax.imageio.ImageIO;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 产生验证码图片的类
 */
public class CapchaHelper {
    private static ImageCaptchaService captchaService;
    private static final Integer MIN_WORD_LENGTH = 4;// 验证码最小长度
    private static final Integer MAX_WORD_LENGTH = 4;// 验证码最大长度
    private static final Integer IMAGE_HEIGHT = 30;// 验证码图片高度
    private static final Integer IMAGE_WIDTH = 130;// 验证码图片宽度
    private static final Integer MIN_FONT_SIZE = 15;// 验证码最小字体
    private static final Integer MAX_FONT_SIZE = 15;// 验证码最大字体
    private static final String RANDOM_WORD = "0123456789";// 随机字符

    // 验证码随机字体
    private static final Font[] RANDOM_FONT = new Font[]{
            new Font("nyala", Font.BOLD, MIN_FONT_SIZE),
            new Font("Arial", Font.BOLD, MIN_FONT_SIZE),
            new Font("Bell MT", Font.BOLD, MIN_FONT_SIZE),
            new Font("Credit valley", Font.BOLD, MIN_FONT_SIZE),
            new Font("Impact", Font.BOLD, MIN_FONT_SIZE)
    };

    // 验证码随机颜色
    private static final Color[] RANDOM_COLOR = new Color[]{
            new Color(255, 255, 255),
            new Color(255, 220, 220),
            new Color(220, 255, 255),
            new Color(220, 220, 255),
            new Color(255, 255, 220),
            new Color(220, 255, 220)
    };

    private static ListImageCaptchaEngine captchaEngine;

    public static CaptchaEngine getCaptchaEngine(final String imgPath) {
        if (captchaEngine == null) {
            synchronized (CapchaHelper.class) {
                if (captchaEngine == null && imgPath != null) {
                    captchaEngine = new ListImageCaptchaEngine() {
                        @Override
                        protected void buildInitialFactories() {
                            RandomListColorGenerator randomListColorGenerator = new RandomListColorGenerator(RANDOM_COLOR);
                            BackgroundGenerator backgroundGenerator = new FileReaderRandomBackgroundGenerator(IMAGE_WIDTH, IMAGE_HEIGHT, imgPath);
                            WordGenerator wordGenerator = new RandomWordGenerator(RANDOM_WORD);
                            FontGenerator fontGenerator = new RandomFontGenerator(MIN_FONT_SIZE, MAX_FONT_SIZE, RANDOM_FONT);
                            TextDecorator[] textDecorator = new TextDecorator[]{};
                            TextPaster textPaster = new DecoratedRandomTextPaster(MIN_WORD_LENGTH, MAX_WORD_LENGTH, randomListColorGenerator, textDecorator);
                            WordToImage wordToImage = new ComposedWordToImage(fontGenerator, backgroundGenerator, textPaster);
                            addFactory(new GimpyFactory(wordGenerator, wordToImage));
                        }
                    };
                }
            }
        }
        return captchaEngine;
    }
    public static ImageCaptchaService generatorCaptchaService(){
        //生成随机颜色，参数分别表示RGBA的取值范围
        RandomRangeColorGenerator textColor = new RandomRangeColorGenerator(new int[]{0,255},new int[]{0,180},new int[]{0,210},new int[]{255,255});
        //随机文字多少和颜色，参数1和2表示最少生成多少个文字和最多生成多少个
        RandomTextPaster randomTextPaster = new RandomTextPaster(MIN_WORD_LENGTH, MAX_WORD_LENGTH, textColor,true);
        //生成背景的大小这里是宽85高40的图片，也可以设置背景颜色和随机背景颜色，这里使用默认的白色
        UniColorBackgroundGenerator colorbgGen = new UniColorBackgroundGenerator(IMAGE_WIDTH,IMAGE_HEIGHT);
        //随机生成的字体大小和字体类型，参数1和2表示最小和最大的字体大小，第三个表示随机的字体
        RandomFontGenerator randomFontGenerator = new RandomFontGenerator(MIN_FONT_SIZE, MAX_FONT_SIZE, new Font[]{new Font("Arial", 0, 10),new Font("Tahoma",0,10)});
        //结合上面的对象构件一个从文本生成图片的对象
        ComposedWordToImage cwti = new ComposedWordToImage(randomFontGenerator,colorbgGen,randomTextPaster);
        //随机文本的字典，这里是使用jcaptcha-1.0-all.jar中的文本字典，字典名称为toddlist.properties
        ComposeDictionaryWordGenerator cdwg = new ComposeDictionaryWordGenerator(new FileDictionary("toddlist"));

        GimpyFactory gf = new GimpyFactory(cdwg, cwti);

        GenericCaptchaEngine gce = new GenericCaptchaEngine(new CaptchaFactory[]{gf});
        //返回一个Service对象，这里180是验证码存在的时间，单位是秒，200000是最大存储大小
        return new GenericManageableCaptchaService(gce,180,200000,75000);
    }
    private static synchronized void initCaptchaService() {
        if (captchaService == null)
            captchaService = generatorCaptchaService();
    }

    public static void  putCaptchaImg(HttpServletRequest req,HttpServletResponse resp) {
        if(captchaService ==null)
            initCaptchaService();
        byte[] captChallengeAsJpeg = null;
        ByteArrayOutputStream jpegOutputStream = new ByteArrayOutputStream();

        String captchaId = req.getSession().getId();
        BufferedImage challenge = captchaService.getImageChallengeForID(captchaId,req.getLocale());
        /*
        JPEGImageEncoder jpegEncoder = JPEGCodec.createJPEGEncoder(jpegOutputStream);
        try {
            jpegEncoder.encode(challenge);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
         */
        try {
            ImageIO.write(challenge,"JPEG",jpegOutputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        captChallengeAsJpeg = jpegOutputStream.toByteArray();
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("Pragma", "no-cache");
        resp.setDateHeader("Expires", 0);
        resp.setContentType("image/jpeg");

        ServletOutputStream respOutputStream = null;
        try {
            respOutputStream = resp.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        try {
            respOutputStream.write(captChallengeAsJpeg);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        try {
            respOutputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        try {
            respOutputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
    }
    public static Boolean checkCaptcha(String checkCode,HttpServletRequest request){
        if (captchaService ==null)
            return false ;
        String sessID = request.getSession().getId();
        try {
            Boolean isResponseCorrect = captchaService.validateResponseForID(sessID, checkCode);
            if(isResponseCorrect){
                return true ;
            }else{
                return  false ;
            }
        }catch (Exception e){
            return  false ;
        }

    }
}