package cn.tianlong.tlobject.network.common;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTCreationException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.apache.commons.lang3.time.DateUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 创建日期：${Date}${time}
 * 描述:
 * 作者:tianlong
 */
public class TLJWT {

    public static String getToken(Map<String, String> claims , String secret ,int expireMinutes,String issure) {
        String token=null;
        try {
            Algorithm algorithm = Algorithm.HMAC256(secret);
            JWTCreator.Builder builder = JWT.create()
                    .withIssuer(issure)
                    .withExpiresAt(DateUtils.addMinutes(new Date(), expireMinutes));
            claims.forEach(builder::withClaim);
            token= builder.sign(algorithm);
        } catch (JWTCreationException exception){
            try {
                throw new Exception("生成token失败");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return token ;
    }

    public static HashMap<String, String> parserToken(String secret, String token,String issure) {
        Algorithm algorithm;
        Map<String, Claim> map ;
        Date expiresAt ;
        DecodedJWT jwt;
        algorithm = Algorithm.HMAC256(secret);
        JWTVerifier verifier = JWT.require(algorithm).withIssuer(issure).build();
        HashMap<String, String> resultMap = new HashMap<>();
        try {
            jwt = verifier.verify(token);
            map = jwt.getClaims();
            expiresAt = jwt.getExpiresAt();
        } catch (TokenExpiredException exception) {
           resultMap.put("expire","true");
            return resultMap;
        } catch (JWTVerificationException exception) {
            resultMap.put("decode","true");
            return resultMap;
        }
        map.forEach((k, v) -> resultMap.put(k, v.asString()));
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        resultMap.put("exp", df.format(expiresAt));
        return resultMap;
    }
}

