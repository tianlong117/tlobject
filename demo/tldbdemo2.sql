/*
 Navicat Premium Data Transfer

 Source Server         : mydql
 Source Server Type    : MySQL
 Source Server Version : 50724
 Source Host           : localhost:3306
 Source Schema         : tldbdemo2

 Target Server Type    : MySQL
 Target Server Version : 50724
 File Encoding         : 65001

 Date: 08/01/2022 14:52:14
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for user2
-- ----------------------------
DROP TABLE IF EXISTS `user2`;
CREATE TABLE `user2`  (
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `number` int(11) NULL DEFAULT NULL,
  `time` datetime(0) NULL DEFAULT NULL,
  PRIMARY KEY (`name`) USING BTREE,
  INDEX `name`(`name`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8 COLLATE = utf8_general_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
