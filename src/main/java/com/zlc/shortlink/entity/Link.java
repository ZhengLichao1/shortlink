package com.zlc.shortlink.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_link")
public class Link {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String shortUri;
    private String originUrl;
    private Integer clickNum;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer delFlag;
}
