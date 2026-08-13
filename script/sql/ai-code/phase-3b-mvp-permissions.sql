-- Phase 3B MVP permissions for AI Coding Remote.
-- Execute manually after reviewing @agent_tenant_id and @agent_role_code.
-- This script does not create tables, indexes, foreign keys, or unique keys.

SET @agent_tenant_id := 1;
SET @agent_role_code := 'common';

INSERT INTO `system_menu`
(`name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`,
 `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
SELECT 'AI Coding', '', 1, 80, 0, '/agent', 'ep:cpu', '', NULL, 0,
       b'1', b'1', b'1', 'admin', NOW(), 'admin', NOW(), b'0'
WHERE NOT EXISTS (
    SELECT 1 FROM `system_menu` WHERE `deleted` = b'0' AND `path` = '/agent' AND `parent_id` = 0
);

SET @agent_menu_id := (
    SELECT `id` FROM `system_menu`
    WHERE `deleted` = b'0' AND `path` = '/agent' AND `parent_id` = 0
    ORDER BY `id` DESC LIMIT 1
);

INSERT INTO `system_menu`
(`name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`,
 `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
SELECT 'AI Coding 工作台', '', 2, 1, @agent_menu_id, 'coding', 'ep:chat-dot-round',
       'agent/coding/index', 'AgentCoding', 0, b'1', b'1', b'1', 'admin', NOW(), 'admin', NOW(), b'0'
WHERE @agent_menu_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM `system_menu`
      WHERE `deleted` = b'0' AND `parent_id` = @agent_menu_id AND `path` = 'coding'
  );

SET @agent_coding_menu_id := (
    SELECT `id` FROM `system_menu`
    WHERE `deleted` = b'0' AND `parent_id` = @agent_menu_id AND `path` = 'coding'
    ORDER BY `id` DESC LIMIT 1
);

INSERT INTO `system_menu`
(`name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`, `component`, `component_name`, `status`,
 `visible`, `keep_alive`, `always_show`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
SELECT *
FROM (
    SELECT '设备查询' AS `name`, 'agent:device:query' AS `permission`, 3 AS `type`, 1 AS `sort`,
           @agent_coding_menu_id AS `parent_id`, '' AS `path`, '' AS `icon`, '' AS `component`,
           NULL AS `component_name`, 0 AS `status`, b'1' AS `visible`, b'1' AS `keep_alive`,
           b'1' AS `always_show`, 'admin' AS `creator`, NOW() AS `create_time`, 'admin' AS `updater`,
           NOW() AS `update_time`, b'0' AS `deleted`
    UNION ALL SELECT '设备配对码创建', 'agent:device:create', 3, 2, @agent_coding_menu_id, '', '', '',
           NULL, 0, b'1', b'1', b'1', 'admin', NOW(), 'admin', NOW(), b'0'
    UNION ALL SELECT '项目查询', 'agent:project:query', 3, 3, @agent_coding_menu_id, '', '', '',
           NULL, 0, b'1', b'1', b'1', 'admin', NOW(), 'admin', NOW(), b'0'
    UNION ALL SELECT 'Session 创建和 Prompt', 'agent:session:create', 3, 4, @agent_coding_menu_id, '', '', '',
           NULL, 0, b'1', b'1', b'1', 'admin', NOW(), 'admin', NOW(), b'0'
    UNION ALL SELECT 'Session 查询', 'agent:session:query', 3, 5, @agent_coding_menu_id, '', '', '',
           NULL, 0, b'1', b'1', b'1', 'admin', NOW(), 'admin', NOW(), b'0'
    UNION ALL SELECT 'Session 停止和关闭', 'agent:session:cancel', 3, 6, @agent_coding_menu_id, '', '', '',
           NULL, 0, b'1', b'1', b'1', 'admin', NOW(), 'admin', NOW(), b'0'
    UNION ALL SELECT 'Relay Ticket 创建', 'agent:relay:createUserTicket', 3, 7, @agent_coding_menu_id, '', '', '',
           NULL, 0, b'1', b'1', b'1', 'admin', NOW(), 'admin', NOW(), b'0'
    UNION ALL SELECT 'Permission 查询', 'agent:permission:query', 3, 8, @agent_coding_menu_id, '', '', '',
           NULL, 0, b'1', b'1', b'1', 'admin', NOW(), 'admin', NOW(), b'0'
    UNION ALL SELECT 'Permission 决策', 'agent:permission:decide', 3, 9, @agent_coding_menu_id, '', '', '',
           NULL, 0, b'1', b'1', b'1', 'admin', NOW(), 'admin', NOW(), b'0'
    UNION ALL SELECT 'ChangeSet 查询', 'agent:changeSet:query', 3, 10, @agent_coding_menu_id, '', '', '',
           NULL, 0, b'1', b'1', b'1', 'admin', NOW(), 'admin', NOW(), b'0'
    UNION ALL SELECT 'Artifact 请求', 'agent:artifact:create', 3, 11, @agent_coding_menu_id, '', '', '',
           NULL, 0, b'1', b'1', b'1', 'admin', NOW(), 'admin', NOW(), b'0'
    UNION ALL SELECT 'Artifact 查询', 'agent:artifact:query', 3, 12, @agent_coding_menu_id, '', '', '',
           NULL, 0, b'1', b'1', b'1', 'admin', NOW(), 'admin', NOW(), b'0'
    UNION ALL SELECT 'Artifact 下载', 'agent:artifact:download', 3, 13, @agent_coding_menu_id, '', '', '',
           NULL, 0, b'1', b'1', b'1', 'admin', NOW(), 'admin', NOW(), b'0'
) AS candidate
WHERE @agent_coding_menu_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM `system_menu`
      WHERE `deleted` = b'0' AND `permission` = candidate.`permission`
  );

SET @agent_role_id := (
    SELECT `id` FROM `system_role`
    WHERE `deleted` = b'0' AND `tenant_id` = @agent_tenant_id AND `code` = @agent_role_code
    ORDER BY `id` DESC LIMIT 1
);

INSERT INTO `system_role_menu`
(`role_id`, `menu_id`, `creator`, `create_time`, `updater`, `update_time`, `deleted`, `tenant_id`)
SELECT @agent_role_id, menu.`id`, 'admin', NOW(), 'admin', NOW(), b'0', @agent_tenant_id
FROM `system_menu` menu
WHERE @agent_role_id IS NOT NULL
  AND menu.`deleted` = b'0'
  AND (
      menu.`id` IN (@agent_menu_id, @agent_coding_menu_id)
      OR menu.`permission` IN (
          'agent:device:query',
          'agent:device:create',
          'agent:project:query',
          'agent:session:create',
          'agent:session:query',
          'agent:session:cancel',
          'agent:relay:createUserTicket',
          'agent:permission:query',
          'agent:permission:decide',
          'agent:changeSet:query',
          'agent:artifact:create',
          'agent:artifact:query',
          'agent:artifact:download'
      )
  )
  AND NOT EXISTS (
      SELECT 1 FROM `system_role_menu` role_menu
      WHERE role_menu.`deleted` = b'0'
        AND role_menu.`tenant_id` = @agent_tenant_id
        AND role_menu.`role_id` = @agent_role_id
        AND role_menu.`menu_id` = menu.`id`
  );
