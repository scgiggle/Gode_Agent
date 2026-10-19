-- Gode 首次部署建表脚本，与 gode.sql 的七张表保持一致。
-- 仅在确认 gode 是本项目的空数据库后，由管理员执行。
-- 不删除表、不覆盖数据；不是已有数据库的升级脚本。
SET NAMES utf8mb4;
USE `gode`;

CREATE TABLE `chat_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(64) NOT NULL COMMENT '会话ID',
  `role` varchar(20) NOT NULL COMMENT '角色: user/assistant/tool/system',
  `content` text COMMENT '消息内容',
  `tool_name` varchar(100) DEFAULT NULL COMMENT '工具名称',
  `tool_call_id` varchar(100) DEFAULT NULL COMMENT '工具调用ID',
  `priority` varchar(20) DEFAULT 'MEDIUM' COMMENT '优先级: CRITICAL/HIGH/MEDIUM/LOW',
  `token_count` int DEFAULT '0' COMMENT '预估 token 数',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session_time` (`session_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话消息';

CREATE TABLE `chat_milestone` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(64) NOT NULL COMMENT '会话ID',
  `type` varchar(30) NOT NULL COMMENT '类型: TASK_CHANGE/ERROR/DECISION/...',
  `content` text COMMENT '内容摘要',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session_time` (`session_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话里程碑';

CREATE TABLE `chat_session` (
  `id` varchar(64) NOT NULL COMMENT '会话ID',
  `agent_id` varchar(64) NOT NULL COMMENT '智能体ID',
  `user_id` varchar(64) NOT NULL COMMENT '用户ID',
  `title` varchar(200) DEFAULT NULL COMMENT '会话标题',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `message_count` int DEFAULT '0' COMMENT '消息数量',
  PRIMARY KEY (`id`),
  KEY `idx_user_agent` (`user_id`,`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话会话';

CREATE TABLE `core_memory` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '用户ID',
  `scope` varchar(20) NOT NULL COMMENT '作用域: user/session',
  `category` varchar(30) NOT NULL COMMENT '分类: Rule/Preference/Decision/Correction/Fact',
  `title` varchar(200) NOT NULL COMMENT '记忆标题',
  `keywords` varchar(500) DEFAULT NULL COMMENT '关键词(逗号分隔)',
  `content` text COMMENT '记忆正文',
  `priority` int NOT NULL DEFAULT '3' COMMENT '优先级1-5(越高越重要)',
  `source_session_id` varchar(64) DEFAULT NULL COMMENT '来源会话ID',
  `use_count` int NOT NULL DEFAULT '1' COMMENT '使用次数',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `last_used_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_priority` (`user_id`,`priority`),
  KEY `idx_user_last_used` (`user_id`,`last_used_at`),
  KEY `idx_keywords` (`keywords`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='核心记忆表(对话内学习+跨会话记忆)';

CREATE TABLE `long_term_memory` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL COMMENT '用户ID',
  `session_id` varchar(64) DEFAULT NULL COMMENT '来源会话ID',
  `memory_type` varchar(50) NOT NULL COMMENT '记忆类型',
  `memory_key` varchar(128) NOT NULL COMMENT '记忆去重键',
  `content` text NOT NULL COMMENT '记忆内容',
  `keywords` varchar(512) DEFAULT NULL COMMENT '关键词集合',
  `source_role` varchar(20) DEFAULT NULL COMMENT '来源角色',
  `confidence` decimal(5,2) DEFAULT '0.50' COMMENT '置信度',
  `hit_count` int DEFAULT '1' COMMENT '命中/更新次数',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_type_key` (`user_id`,`memory_type`,`memory_key`),
  KEY `idx_user_time` (`user_id`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='长期记忆';

CREATE TABLE leetcode_mistake (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '用户 ID',
  problem_id VARCHAR(64) NOT NULL COMMENT '力扣题号，例如 1、3、15',
  problem_slug VARCHAR(128) NOT NULL COMMENT '力扣 URL slug，例如 two-sum',
  title VARCHAR(200) NOT NULL COMMENT '题目名称',
  difficulty VARCHAR(20) NOT NULL DEFAULT '简单' COMMENT '难度：简单/中等/困难',
  error_summary TEXT COMMENT '错误摘要，列表页展示',
  tags VARCHAR(500) DEFAULT NULL COMMENT '标签，逗号分隔，例如：哈希表,滑动窗口',
  status VARCHAR(20) NOT NULL DEFAULT '待复习'  COMMENT '学习状态：待复习/复习中/已掌握',
  next_review_time TIMESTAMP NULL DEFAULT NULL COMMENT '下次复习时间',
  review_count INT NOT NULL DEFAULT 0 COMMENT '已完成复习次数',
  source_session_id VARCHAR(64) DEFAULT NULL COMMENT '来源 AI 对话会话 ID',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
-- 防止同一用户对同一道题创建多条主记录
  UNIQUE KEY uk_user_problem (user_id, problem_id),
-- 列表页：按用户、状态、复习时间查询
  KEY idx_user_status_time (user_id, status, next_review_time),
-- 插件按 slug 跳转或查询时使用
  KEY idx_user_slug (user_id, problem_slug),
  KEY idx_user_updated (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '力扣错题本主表';

CREATE TABLE leetcode_mistake_review (
     id BIGINT NOT NULL AUTO_INCREMENT,
     mistake_id BIGINT NOT NULL COMMENT '关联 leetcode_mistake.id',
     fail_reason TEXT COMMENT '本次失败原因',
     fail_case TEXT COMMENT '失败用例、编译错误或力扣判题结果',
     ai_analysis TEXT COMMENT 'AI 完整分析',
     action_plan TEXT COMMENT '下次改进方案',
     source_session_id VARCHAR(64) DEFAULT NULL COMMENT '来源 AI 对话会话 ID',
     created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '复盘创建时间',
     PRIMARY KEY (id),
-- 查询某道题的复盘历史、最新复盘
     KEY idx_mistake_time (mistake_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '力扣错题复盘历史表';

