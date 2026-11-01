USE shortlink;

CREATE TABLE t_link (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    short_uri      VARCHAR(16)   NOT NULL COMMENT '短码',
    origin_url     VARCHAR(1024) NOT NULL COMMENT '原始长链',
    click_num      INT           NOT NULL DEFAULT 0 COMMENT '点击次数',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    del_flag       TINYINT       NOT NULL DEFAULT 0 COMMENT '0正常 1删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_uri (short_uri)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短链表';

CREATE TABLE t_link_access_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    link_id     BIGINT       NOT NULL,
    ip          VARCHAR(64)  DEFAULT NULL,
    user_agent  VARCHAR(512) DEFAULT NULL,
    access_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_link_time (link_id, access_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='访问日志';
