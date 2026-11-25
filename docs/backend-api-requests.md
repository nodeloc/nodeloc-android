# App 需要后端补的接口（按 2026-11 设计稿）

依据：设计稿 Android 页（日常 / 管理 / 个人三排），对照后端 `nodeloc/nodeloc` 提交 `47ac0dc` 的代码。
App 端遇到下面这些缺口时，一律是**先不做或降级**，没有假数据。每条写了：设计要什么、后端现在怎样、建议的接口。
优先级：**P0** 影响管理功能能不能正常用；**P1** 设计里有、App 现在缺；**P2** 体验优化。

约定沿用现有接口：路径在 `/api/v1` 下，错误 `{"error":{"code","message"}}`，请求体严格（多字段、空体都 400）。

---

## 一、权限与待处理数

### 1. `/me/nodes` 带出主理人身份和每个节点的待处理数　**P0**
- 设计：侧边栏「主理的节点」每行显示待处理数，菜单按钮上显示总数。
- 现状：`web/mynodes_api.go` 的 `myNodeJSON` 丢掉了查询里已经算好的 `owned` 和 `reports`、`queued`、`join_requests`（`db/queries/node/mine.sql:13`、`:30-46`）。`role` 只有 `member` / `moderator`，主理人也是 `moderator`（`node/service.go:466`），文档 `mobile-api.md:337` 说会有 `owner`，和代码不一致。
- 现在 App 的做法：对每个管理的节点各拉一次 `GET /nodes/{slug}/review?limit=50`，数条数，最多显示 50+。
- 建议：`joined[]` 每项加
  ```json
  {"owner": true, "pending": {"reports": 3, "queued": 9, "join_requests": 0}}
  ```
  只对能管理该节点的人返回 `pending`；`role` 对主理人返回 `owner`（或保持 `moderator` 另加 `owner` 布尔，二选一，同步改 `mobile-api.md`）。

### 2. 节点版主的待处理数推送　**P1**
- 现状：SSE `/notifications/stream` 的 `review` 事件只发给有 `admin.review` 的人，数的是全站所有待审项（`moderation/pending_stream.go`）。节点版主收不到任何数。
- 建议：同一事件里给节点版主发 `{"pending": N, "nodes": {"indie": 12, "dev": 5}}`，`pending` 是他能处理的总数；只有全站权限的人才算全站。

### 3. 「版务」通知分类，并通知节点版主　**P1**
- 设计：消息页通知分类「全部 / 回复 / @我 / 版务 / 系统」。
- 现状：所有管理类通知都是 `kind:"system"`，`GET /notifications` 的筛选没有 `moderation`（`notify/service.go:418`）。`NotifyStaff` 只发给 `role` 是 moderator / admin 的人（`db/queries/notify/notifications.sql:72`），通过用户组拿到 `admin.review` 的人和节点版主都收不到。
- 现在 App 的做法：把 `data.path` 以 `/admin/` 或 `/n/{slug}/manage` 开头的系统通知归到「版务」。
- 建议：
  - 新 kind `moderation`，筛选参数 `filter=moderation`；
  - 节点里有新举报 / 新待审帖子时，通知该节点的版主（不只是全站 staff）；
  - 按权限 `admin.review` 发，而不是按 `role`。

---

## 二、审核队列

### 4. 待审项的结构化原因　**P0**
- 设计：队列筛选「新用户 / 含外链 / 命中关键词」。
- 现状：`pending_post` 只在 `payload.reason` 里放中文句子（`命中屏蔽词`、`作者正在被观察`、`新注册账号，发帖先审核`），没有代码；完全没有「含外链」的信号。App 只能按中文原文匹配，英文站点或文案一改就失效。
- 建议：`payload` 加 `"reason_code": "watched_word" | "observed" | "newcomer"`，再加 `"has_links": true`、`"matched_words": ["…"]`（设计里要把命中的词标出来）。

### 5. 待审项带作者概况　**P1**
- 设计：卡片显示「像素君 · 注册 3 天」，举报详情显示作者的注册时长、发帖数、被删数、被举报数。
- 现状：item 只有 `target_username` / `target_user_id`，没有头像、注册时间、等级；作者统计要另调 `GET /admin/users/{id}`，节点版主没有 `admin.users` 权限，调不了。
- 建议：item 加
  ```json
  {"author": {"avatar": "…", "created_at": "…", "trust_level": 0, "tier_name": "青铜",
              "posts": 9, "deleted_posts": 2, "times_reported": 4}}
  ```
  节点队列里也返回（只限该作者在本节点的数据也可以）。

### 6. 节点队列和全站队列能力对齐　**P1**
- 现状：`GET /nodes/{slug}/review` 不支持 `kind`、`item` 筛选，不返回 `counts` / `kind_counts`；节点版主不能 `reopen`、`restore-post`。
- 建议：节点队列支持 `kind`、`item`，返回 `counts` 和 `kind_counts`；给节点版主开放本节点内的 `reopen` 和 `restore-post`。

### 7. 处理结果通知举报人　**P2**
- 设计：通知里有「你的举报已处理 ·“…”已被版主删除」。
- 现状：举报人永远收不到结果（`moderation/service.go:709` 附近）。
- 建议：处理举报后给每位举报人发一条通知（`moderation` kind），说明处理结果。

---

## 三、内容处理

### 8. 删除时的「通知作者」「允许申诉」　**P1**
- 设计：删除弹层有两个开关；所有处理都要选理由，作者会收到通知并可申诉。
- 现状：
  - 队列里删除总会通知作者，没有开关；
  - `DELETE /posts/{id}`（在帖子里直接删回复）不通知作者，只把理由写在占位上；
  - `DELETE /topics/{id}` 没有理由、不通知；删 1 楼时理由被丢掉（`content/posts.go:411`、`content/topics.go:851`）；
  - 没有针对内容删除的申诉接口（`/risk/appeals` 只管风控）。
- 建议：
  - `DELETE /topics/{id}` 和 `DELETE /posts/{id}` 都收 `{"reason": "…", "notify": true, "appealable": true}`，并通知作者；
  - 新增 `POST /api/v1/appeals`（内容申诉，进审核队列 kind `content_appeal`）。

### 9. 隐藏帖子　**P1**
- 设计：版主操作里有「隐藏帖子 · 作者修改后可以重新提交」。
- 现状：没有直接隐藏的接口，只能经由审核队列的 `hide_post` 或被 3 人举报自动隐藏（moderation-api §6.4）。
- 建议：`PUT /api/v1/posts/{id}/hidden` `{"hidden": true, "reason": "…"}`，作者编辑后自动重新进入审核。

### 10. 编辑历史回滚　**P2**
- 现状：没有回滚接口，App 和网页一样用 `PATCH /posts/{id}` 提交旧版本内容（会生成一个新版本）。能用，但服务端无法区分这是回滚。
- 建议（可选）：`POST /api/v1/posts/{id}/revisions/{n}/restore`，审计里记 `post.rollback`。

### 11. 列表和回复上的举报数（仅版主可见）　**P1**
- 设计：首页列表卡片「3 人举报 · 去处理 · 仅版主可见」；回复上「6 人举报」。
- 现状：`GET /topics` 和帖子楼层 JSON 都不带举报数。
- 建议：调用者能管理该节点时，主题摘要加 `"report_count": 3, "review_item_id": 123`，楼层加同样两个字段；其他人不返回。

---

## 四、处罚

### 12. 处罚选项：删除近期内容、是否通知　**P1**
- 设计：处罚弹层有「删除最近 24 小时的内容」「通知用户」。
- 现状：`POST /admin/users/{id}/penalties` 只收 `kind`、`reason`、`days`，必定通知并发邮件；节点内限制（`/nodes/{slug}/restrictions`）则完全不通知。
- 建议：两个接口都加 `"delete_recent_hours": 24`（0 表示不删）和 `"notify": true`；节点内限制默认也要通知用户。

### 13. 节点版主限制他人时的保护（Bug）　**P0**
- 现状：`node/manage.go:210` 的注释说节点版主不能限制其他版主或站点管理人员，但 `:222` 的代码没有检查，现在可以。
- 建议：按注释补上检查，返回 403 `protected`。

### 14. 节点内禁言的时长上限　**P1**
- 设计说明的权限表：节点版主「只在本节点，最长 30 天」；管理员「全站，可永久」。
- 现状：节点内限制 `days` 允许 0（永久）到 3650。
- 建议：节点版主（非站点 staff）限制在 1–30 天，不能永久；站点 staff 不受限。或者明确不改，App 按 30 天上限做前端限制。

### 15. 观察名单　**P1**
- 设计：用户管理里「加入观察名单 · 之后的发帖都先进审核队列」。
- 现状：风控里已经有「作者正在被观察」这个原因（`moderation/words.go:152`），但没有给版主手动加 / 移除的接口。
- 建议：`PUT /api/v1/admin/users/{id}/watch` `{"watch": true, "reason": "…", "days": 30}`、`DELETE` 同路径；`GET /admin/users/{id}` 返回当前状态。节点范围的观察名单可选：`PUT /nodes/{slug}/watch/{user_id}`。

---

## 五、操作日志与撤销

### 16. 日志条目带「能否撤销」和撤销接口　**P1**
- 设计：操作日志每条都可以撤销，「所有版主的操作都可以撤销或复查」，版主可撤销自己的操作，管理员可撤销任何人的。
- 现状：没有撤销接口，也没有「可撤销」标记；App 自己按动作类型映射到反向操作，只覆盖了禁言 / 封号和队列里的删除 / 隐藏。
- 建议：
  - 条目加 `"undoable": true` 和 `"undone_at"`；
  - `POST /api/v1/admin/audit-logs/{id}/undo`、`POST /nodes/{slug}/mod-log/{id}/undo`，服务端执行反向操作并写一条新日志；
  - 按设计的权限表判断谁能撤销谁的操作。

### 17. 节点日志带目标用户 id　**P1**
- 现状：`GET /nodes/{slug}/mod-log` 的 `target` 只有用户名，没有 id，`restrict.*` 这类操作在 App 里无法撤销（解除限制要 user id）。
- 建议：条目加 `"target_id"` 和 `"target_type"`。

---

## 六、管理台

### 18. 管理台统计　**P2**
- 设计：今天「已处理 / 删除 / 封禁 / 平均响应」。
- 现状：`GET /admin/stats` 没有这些；App 现在翻当天的审计日志自己数（最多 6 页），没有平均响应。节点范围没有对应接口。
- 建议：`GET /api/v1/admin/stats/today?node=<slug>` 返回
  `{"resolved": 23, "deleted": 6, "penalized": 1, "median_response_minutes": 14}`，节点版主可查自己节点。

---

## 七、节点

### 19. 节点置顶帖列表　**P2**
- 设计：节点管理「置顶帖 · 2 个」，节点页置顶轮播。
- 现状：没有置顶相关的列表接口，只能在主题列表里找 `pinned:true` 的。
- 建议：`GET /api/v1/nodes/{slug}/pins`（含到期时间）；或在 `GET /nodes/{slug}/manage` 里返回 `pinned_count`。

### 20. 频道：状态、检查间隔、版主权限　**P2**
- 设计：频道卡片显示「正常 / 已暂停 / 出错」和「每 30 分钟检查」「每 15 分钟检查」，每个频道间隔不同。
- 现状：没有 `status` 字段（App 按 `enabled` / `last_error_code` 推算）；间隔固定 15 分钟（`channel/service.go:43`）；只有主理人能管频道，节点版主 403。
- 建议：加 `"status": "ok" | "paused" | "error"`；如果要支持每个频道单独设间隔，加 `poll_minutes` 字段；是否开放给节点版主看（只读）需要产品决定。

---

## 八、首页与日常

### 21. 首页「上升中」排序　**P1**
- 设计：首页排序「热门 / 最新 / 精华 / 上升中 / 关注」。
- 现状：网页有 `/rising`（`web/pages.go:60`），但 `GET /api/v1/topics` 只认 `sort=hot`，App 拿不到上升中。
- 建议：`GET /api/v1/topics?sort=rising`，翻页方式同 `hot`（`offset` / `next_offset`）。

### 22. 首页公告轮播　**P1**
- 设计：首页排序标签下面是公告轮播（如「维护通知 · 11 月 25 日」）。
- 现状：`announce/home.go` 的 `HomeBanners` 只给服务端渲染的网页首页用（`web/home_announce.go`），没有 `/api/v1` 路由能列出首页公告；公开的只有关闭公告的 `PUT /api/v1/announcements/{id}/dismissal`。App 暂不显示轮播。
- 建议：`GET /api/v1/announcements/home` →
  ```json
  {"announcements":[{"id":1,"kind":"maintenance","kind_name":"维护通知","title":"…","excerpt":"…",
                     "cover_url":null,"topic_id":18,"url":null,"published_at":"…","dismissible":true}]}
  ```
  已被读者关闭的不返回；关闭继续用现有的 dismissal 接口。

### 23. 节点内的「热门」排序　**P1**
- 设计：节点页排序「热门 / 最新 / 精华」。
- 现状：`GET /api/v1/topics?sort=hot` 会忽略 `node=`，返回的是全站热门（`content/handlers.go` `listTopics` 走 `ListHotTopics` 时不看节点）。App 原来的节点页「热门」其实一直显示的是最新列表，这次已去掉，节点页只剩「最新 / 精华 / 关于」。
- 建议：`sort=hot`（以及第 21 条的 `sort=rising`）支持 `node=` 和 `tag=`。

### 24. 节点在线人数　**P2**
- 设计：节点页「2.4 万成员 · 318 在线」。
- 现状：在线数只渲染在网页里，没有接口返回。App 暂时显示主题数。
- 建议：`GET /api/v1/nodes/{slug}` 加 `"online_count"`（口径同网页：登录用户，15 分钟窗口）。

### 25. 主题详情带「最佳答案」　**P1**
- 设计：回复区顶部「最佳答案 · 由楼主采纳」卡片。
- 现状：主题 / 楼层 JSON 里没有采纳信息。App 现在去翻公开的活动日志（`GET /topics/{id}/log`，最多 3 页）找最新的 `solved` / `unsolved` 记录，被采纳的楼层没加载时再单独拉一次 `thread?parent=N`。打开每个主题都多出这些请求。
- 建议：`GET /topics/{id}`（及 `thread`）的 `topic` 加
  `"accepted_answer": {"post_id": 1, "post_number": 12, "username": "kiwi", "accepted_by": "author" | "moderator"}`；
  另外返回 `"can_accept": true`（是否打了求助标签、调用者能否采纳），App 才能只在可采纳的主题上显示「采纳」菜单。

### 26. 已删除楼层带删除原因和删除人　**P1**
- 设计：「这条回复已被版主删除 · 广告推广」。
- 现状：thread 里的已删除楼层不带原因、不带是谁删的；App 也是从活动日志的 `post_deleted` 条目里找 `details.reason`。
- 建议：已删除楼层加 `"deleted_by": "author" | "moderator"`、`"delete_reason": "…"`。

### 27. 相关主题　**P1**
- 设计：回复区下方「相关主题」，分「相关推荐 / n/节点 / 本周热门」，可「换一批」。
- 现状：没有相关主题接口，网页是服务端渲染时算的。App 用节点最新列表和全站热门凑了两个分组，没有「相关推荐」，「本周热门」也只能叫「热门」。
- 建议：`GET /api/v1/topics/{id}/related?kind=similar|node|week&offset=` → `{"topics":[主题摘要…],"next_offset":…}`。

### 28. 主题摘要和搜索结果带「已解决」　**P2**
- 设计：搜索结果、相关主题前面有「已解决」小标签。
- 现状：主题摘要（`GET /topics`）和搜索结果都没有已解决标记；搜索结果也没有 `first_post_id` / `viewer_vote`，列表上不能直接投票。
- 建议：主题摘要和 `type=topics` 的搜索结果加 `"solved": true`、`"first_post_id"`、`"viewer_vote"`。

### 29. 系统通知的多语言文本　**P2**
- 现状：系统通知的 `data` 里是存好的中文 `title` / `body`，`tpl` 模板引用的是网页端的字符串表，App 没有。非中文用户看到的是中文。
- 建议：通知接口按请求语言（`Accept-Language` 或用户设置）把 `tpl` 渲染好再返回。

## 九、个人与成长

### 30. 财富榜：周期、头像、等级、名次变化　**P1**
- 设计：「总榜 / 本周 / 本月」三个标签；每行有头像、段位、名次升降（↑2 / ↓1）；「按能量排名 · 14:00 更新」。
- 现状：`GET /points/leaderboard` 只有总榜，每行只有名次、用户名、余额，没有更新时间。App 只做了总榜，头像用首字母。
- 建议：加 `?period=all|week|month`；每行加 `avatar_url`、`tier_name`、`rank_change`；响应加 `updated_at`。

### 31. 声望：按周统计和升级预估　**P2**
- 设计：「怎么涨声望 · 本周 +222」每个来源的本周数；任务「6 / 10」；「按近 30 天的速度约 6 周后升级」；「升到王者后解锁」列表；「已锁定」标记。
- 现状：`/me/reputation` 只有各来源的累计数，没有本周数、任务进度、近 30 天增速；`/tiers` 没有每级解锁的权益；没有「管理员手动锁定」状态。App 显示「累计 +N」，其余省略。
- 建议：`/me/reputation` 加 `week_by_source`、`tasks: {"done":6,"cap":10}`、`rate_30d`、`locked`；`/tiers` 每级加 `unlocks: [{"label","from","to"}]`。

### 32. 用户主页：成就数、主理的节点　**P2**
- 设计：用户主页「12 个成就」「主理 n/indie」。
- 现状：别人的成就只能拿到他置顶的那几个（`/users/{u}/achievements`），没有总数；没有接口列出一个用户主理的节点。
- 建议：`GET /users/{u}` 加 `"achievement_count"` 和 `"owned_nodes": [{"slug","name"}]`。

### 33. 能量：成就带来的能量、兑换入口　**P2**
- 设计：成就页「成就累计带来 640 声望 · 1,850 能量」；能量页「去兑换」。
- 现状：后端不统计成就发放的能量总数；也没有可以用能量兑换的东西。两处都省略了。
- 建议：成就概览加 `"energy_earned"`；兑换要等产品先定有什么可兑换。

---

## 附：已经对齐、无需改动的

- `GET /me/permissions` 足够判断全站权限。
- 审核队列（全站）、处罚、备注、审计日志、节点限制、节点管理（规则、成员、版主、访问名单、统计）、频道的列表 / 暂停 / 同步、编辑历史（`/posts/{id}/revisions`）都已接入 App。

> 第 3、4 批（日常页、个人页）还在做，做完会把新发现的缺口补进这份文档。
