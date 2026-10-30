CREATE TABLE t_knowledge_base (
    id                    BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键自增ID',
    name                  VARCHAR(128)  NOT NULL                COMMENT '知识库名称，业务分组标识',
    description           VARCHAR(512)  NULL                    COMMENT '知识库描述',
    document_count        BIGINT        NOT NULL DEFAULT 0      COMMENT '文档总数',
    embedding_dimension   INT           NULL                    COMMENT '向量维度',
    embedding_enabled     TINYINT(1)    NOT NULL DEFAULT 1      COMMENT '是否启用向量化',
    embedding_model       VARCHAR(64)   NULL                    COMMENT 'embedding 提供者/模型',
    created_at            BIGINT        NOT NULL                COMMENT '创建时间(epoch ms)',
    updated_at            BIGINT        NOT NULL                COMMENT '更新时间(epoch ms)',
    deleted               TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '软删除标记',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库元数据';