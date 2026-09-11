-- MySQL 测试 schema 敏感配置加密字段长度调整
ALTER TABLE data_datasource
  MODIFY COLUMN password TEXT NOT NULL COMMENT '密码，启用配置加密后以 enc:gcm: 前缀密文存储';

ALTER TABLE data_model_config
  MODIFY COLUMN api_key TEXT NOT NULL COMMENT 'API密钥，启用配置加密后以 enc:gcm: 前缀密文存储';

-- 如测试 schema 已新增 proxy_password 字段，再执行：
-- ALTER TABLE data_model_config
--   MODIFY COLUMN proxy_password TEXT NULL COMMENT '代理密码，启用配置加密后以 enc:gcm: 前缀密文存储';
