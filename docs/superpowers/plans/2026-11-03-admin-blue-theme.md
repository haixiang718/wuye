# 管理后台简洁蓝白主题 Implementation Plan

> **For agentic workers:** Use executing-plans to implement the approved design in this session. Work in the existing `codex/billing-flow-repairs` checkout because it serves the user's local runtime; stage only the UI task's files.

**Goal:** 将已确认的 A 方案应用到现有管理后台，并在真实浏览器中验证。

**Architecture:** `admin.html` 加载独立的 `admin-blue.css`，通过 `body.admin-blue` 限定样式。账单模板仅增加展示标签、金额对齐和状态样式，保留所有原有 Vue 模型与事件。

**Tech Stack:** Vue 2、Bootstrap 4、Express、CSS、Playwright / Edge。

---

### Task 1：基线与主题接入

- [x] 查看共享模板及原样式，记录已有未提交文件，运行 `npm test`（12 项通过）。
- [x] 新增 `wuye-f/MicroCommunityWeb-v0.8/public/css/admin-blue.css`，以 `body.admin-blue` 作为作用域，设置 `--admin-accent: #2563eb`、`--admin-sidebar: #121e33`、`--admin-background: #f5f7fb`、`--admin-border: #e5eaf1`。
- [x] 在 `admin.html` 最后一个样式链接后添加 `<link rel="stylesheet" href="/css/admin-blue.css?v=20261103">`，并在 body 保留 `vc-body` 且增加 `admin-blue`。
- [x] 在现有后台分支原地实施，保留其他业务修复文件；此次不安装依赖、不改服务配置。

### Task 2：实际页面与浏览器检查

- [x] 账单页添加 `admin-bills` 根类及查询标签；金额单元格使用 `admin-amount`，审核状态使用 `admin-status` 并按 `reviewState` 显示 `is-published` 或 `is-draft`。保持所有原有 `v-model`、`v-if`、`v-on` 和数据表达式。
- [x] 用现有登录会话打开真实账单、费用配置、业主与房屋页面并保存本地截图。打开新增和修改费用弹窗后取消，检查日期选择器和导航菜单折叠，不点击业务保存、审核或推送。
- [x] 浏览器在 1440 / 1024 像素验证页面滚动与表格滚动；核对按钮禁用及焦点、弹窗和下拉层没有被遮挡。发现布局冲突就缩小 CSS 选择器范围后再检查。
- [x] 运行 `npm test`、`git diff --check`；使用 `git diff` 确认没有接口、计费、权限和数据逻辑变动。低风险视觉修改采用浏览器验收，不新增镜像样式实现的单元测试。

### Task 3：交付

- [x] 将已完成的步骤打勾并记录验证结果；仅提交本次主题、账单模板及设计/计划文件。
- [x] 给用户当前后台链接和强制刷新说明，说明实际覆盖范围及未验证限制。
