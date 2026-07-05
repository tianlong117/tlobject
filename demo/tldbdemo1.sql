/*
 Navicat Premium Data Transfer

 Source Server         : mydql
 Source Server Type    : MySQL
 Source Server Version : 50724
 Source Host           : localhost:3306
 Source Schema         : tldbdemo1

 Target Server Type    : MySQL
 Target Server Version : 50724
 File Encoding         : 65001

 Date: 08/01/2022 14:52:04
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for user
-- ----------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user`  (
  `name` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
  `number` int(11) NULL DEFAULT NULL,
  `time` datetime(0) NULL DEFAULT NULL,
  PRIMARY KEY (`name`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8 COLLATE = utf8_general_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for user1
-- ----------------------------
DROP TABLE IF EXISTS `user1`;
CREATE TABLE `user1`  (
  `name` varchar(250) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `number` int(11) NULL DEFAULT NULL,
  `time` varchar(255) CHARACTER SET latin1 COLLATE latin1_swedish_ci NULL DEFAULT NULL,
  PRIMARY KEY (`name`) USING BTREE,
  INDEX `idx_name`(`name`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = latin1 COLLATE = latin1_swedish_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
 -- 会话表
CREATE TABLE `ai_sessions` (
                               `session_id` varchar(255) NOT NULL,
                               `user_id` varchar(255) DEFAULT NULL,
                               `system_message` text,
                               `created_at` bigint DEFAULT NULL,
                               `last_active` bigint DEFAULT NULL,
                               `metadata` varchar(2048) DEFAULT NULL,
                               PRIMARY KEY (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

-- 记忆条目表
CREATE TABLE `ai_memory` (
                             `id` int NOT NULL AUTO_INCREMENT,
                             `session_id` varchar(255) DEFAULT NULL,
                             `mem_key` varchar(512) DEFAULT NULL,
                             `mem_value` text,
                             `mem_type` varchar(50) DEFAULT NULL,
                             `tag` varchar(255) DEFAULT NULL,
                             `created_at` bigint DEFAULT NULL,
                             `expires_at` bigint DEFAULT NULL,
                             `metadata` varchar(2048) DEFAULT NULL,
                             PRIMARY KEY (`id`),
                             KEY `idx_session_tag_time` (`session_id`, `tag`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;
