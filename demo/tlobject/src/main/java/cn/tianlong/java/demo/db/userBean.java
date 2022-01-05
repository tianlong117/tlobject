package cn.tianlong.java.demo.db;

import java.util.Date;

/**
 * 创建日期：2021/3/2817:49
 * 描述:
 * 作者:tianlong
 */
public class userBean {
    private String name ;
    protected int number ;
    protected Date date ;
    public userBean() {

    }
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
    }

    public Date getDate() {
        return date;
    }

    public void setDate(Date date) {
        this.date = date;
    }


}
