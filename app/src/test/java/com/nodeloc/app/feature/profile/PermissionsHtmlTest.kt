package com.nodeloc.app.feature.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/permissions` as `templates/permissions.html` renders it (backend
 * internal/web), trimmed to a few columns and rows but otherwise the
 * template's own markup and whitespace: the wide table first, which must be
 * ignored, then the narrow `.pm-m` block the app reads, once per table.
 */
class PermissionsHtmlTest {

    private val tierIcon = """<svg class="tier-ico" width="18" height="18" viewBox="0 0 32 32" fill="currentColor" fill-rule="evenodd" aria-hidden="true" focusable="false"><path d="M9 4h14l7 12-7 12H9L2 16Zm7 7a5 5 0 1 0 0 10 5 5 0 1 0 0-10Z"/></svg>"""
    private val guestIcon = """<svg width="18" height="18" viewBox="0 0 32 32" fill="currentColor" fill-rule="evenodd" aria-hidden="true"><path d="M16 4.5a5.5 5.5 0 1 0 0 11 5.5 5.5 0 1 0 0-11zM5 27.5C5 21.8 9.9 18 16 18s11 3.8 11 9.5z"/></svg>"""
    private val yes = """<svg class="pm-yes" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg><span class="sr-only">可以</span>"""
    private val no = """<svg class="pm-no" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true"><path d="M8 12h8"/></svg><span class="sr-only">不可以</span>"""

    private val page = """
<!doctype html>
<html lang="zh-CN"><head><title>用户组与权限 · NodeLoc</title>
<script>window.NL = {}; document.write('<div class="pm-m" data-pick="9">');</script>
<style>.pm-m { display: none; }</style></head>
<body>
<div class="cols pm-page" data-wide data-pm-page>
  <main class="main pm-main">
    <div class="pm-panel" id="pm-panel-tier" role="tabpanel" aria-labelledby="pm-tab-tier" data-pm-panel="tier">
<section class="pm-table" data-pm-view aria-label="各段位权限对比" data-pick="1" style="--pm-cols: 2">
  <div role="table" aria-label="各段位权限对比">
    <div role="row" class="pm-hrow">
      <div role="columnheader" class="pm-ch" data-c="0">
        <button type="button" class="pm-colbtn" data-pm-pick="0" aria-pressed="false">
          <span class="pm-ic">$guestIcon</span>
          <span class="pm-cn">游客</span>
        </button>
      </div>
    </div>
    <div role="row" class="pm-row" data-pm-row="browse">
      <div role="rowheader" class="pm-rh"><span class="pm-rl">WIDE TABLE</span></div>
    </div>
  </div>
</section>

<div class="pm-m" data-pm-view data-pick="1">
  <div class="pm-chips" role="group" aria-label="选一列">
    <button type="button" class="pm-chip" data-pm-pick="0" aria-pressed="false"><span class="pm-ic">$guestIcon</span>游客</button><button type="button" class="pm-chip" data-pm-pick="1" aria-pressed="true"><span class="pm-ic">$tierIcon</span>青铜</button>
  </div>
  <div class="pm-card" data-c="0">
    <span class="pm-card-ic">$guestIcon</span>
    <div class="pm-card-text">
      <div class="pm-card-name"><span>游客</span></div>
      <div class="pm-card-sub">未登录 · 没有登录时能做的事</div>
    </div>
  </div>
  <div class="pm-card" data-c="1">
    <span class="pm-card-ic">$tierIcon</span>
    <div class="pm-card-text">
      <div class="pm-card-name"><span>青铜</span><span class="pm-mine">你的段位</span></div>
      <div class="pm-card-sub">注册即有 · 比游客多 2 项</div>
    </div>
  </div>
  <p class="pm-m-intro">段位越高，能参与的方式越多。标「新」的是从这一级开始放开或变宽的。</p>
  <div class="pm-list">
    <div class="pm-lgroup">阅读</div>
    <div class="pm-li" data-pm-row="browse">
      <div class="pm-li-main">
        <div class="pm-li-label"><span>浏览公开的帖子和节点</span></div>
      </div>
      <div class="pm-lv" data-c="0">$yes</div><div class="pm-lv" data-c="1">$yes</div>
    </div>
    <div class="pm-li" data-pm-row="scope">
      <div class="pm-li-main">
        <div class="pm-li-label"><span>能看的帖子范围</span><span class="pm-new" data-c="1">新</span></div>
        <div class="pm-rn">作者可以给帖子设阅读门槛</div>
      </div>
      <div class="pm-lv" data-c="0"><span>公开帖子</span></div><div class="pm-lv" data-c="1"><span>全部</span></div>
    </div>
    <div class="pm-lgroup">发帖与互动</div>
    <div class="pm-li" data-pm-row="likes">
      <div class="pm-li-main">
        <div class="pm-li-label"><span>点赞</span><span class="pm-new" data-c="1">新</span></div>
        <div class="pm-rn">收到的赞每天最多计 40 声望</div>
      </div>
      <div class="pm-lv is-no" data-c="0">$no</div><div class="pm-lv" data-c="1"><span>每天 150 次&#43; &amp; 更多</span></div>
    </div>
  </div>
</div>
    </div>
    <div class="pm-panel" id="pm-panel-role" role="tabpanel" data-pm-panel="role" hidden>
<div class="pm-m" data-pm-view data-pick="0">
  <div class="pm-chips" role="group" aria-label="选一列">
    <button type="button" class="pm-chip" data-pm-pick="0" aria-pressed="true"><span class="pm-ic">$guestIcon</span>成员</button>
  </div>
  <div class="pm-card" data-c="0">
    <span class="pm-card-ic">$guestIcon</span>
    <div class="pm-card-text">
      <div class="pm-card-name"><span>成员</span><span class="pm-mine">你的身份</span></div>
      <div class="pm-card-sub">任意段位 · 所有成员都有的权限</div>
    </div>
  </div>
  <p class="pm-m-intro">管理身份由节点创建者任命或站务团队授予，和段位无关。</p>
  <div class="pm-list">
    <div class="pm-lgroup">帖子</div>
    <div class="pm-li" data-pm-row="accept">
      <div class="pm-li-main">
        <div class="pm-li-label"><span>标记最佳答案</span></div>
      </div>
      <div class="pm-lv" data-c="0"><span>自己的主题</span></div>
    </div>
  </div>
</div>
    </div>
  </main>
</div>
</body></html>
""".trimIndent()

    private val matrix by lazy { requireNotNull(PermissionsHtml.parse(page)) }

    @Test
    fun readsTheNarrowMarkupNotTheWideTable() {
        val tiers = matrix.tiers
        assertEquals(1, tiers.mine)
        assertEquals(listOf("游客", "青铜"), tiers.columns.map { it.name })
        assertTrue(tiers.groups.flatMap { it.rows }.none { it.label == "WIDE TABLE" })
    }

    @Test
    fun columnsCarryTheirCardAndGlyph() {
        val (guest, bronze) = matrix.tiers.columns
        assertNull(guest.mineLabel)
        assertEquals("未登录 · 没有登录时能做的事", guest.sub)
        assertEquals("你的段位", bronze.mineLabel)
        assertEquals("注册即有 · 比游客多 2 项", bronze.sub)
        assertEquals("M9 4h14l7 12-7 12H9L2 16Zm7 7a5 5 0 1 0 0 10 5 5 0 1 0 0-10Z", bronze.iconPath)
    }

    @Test
    fun rowsGroupUnderTheirHeadings() {
        val groups = matrix.tiers.groups
        assertEquals(listOf("阅读", "发帖与互动"), groups.map { it.title })
        assertEquals(listOf("browse", "scope"), groups[0].rows.map { it.key })
        assertEquals("段位越高，能参与的方式越多。标「新」的是从这一级开始放开或变宽的。", matrix.tiers.intro)
    }

    @Test
    fun cellsAreTickDashOrText() {
        val rows = matrix.tiers.groups.flatMap { it.rows }.associateBy { it.key }
        assertEquals(PermValue.Yes, rows.getValue("browse").values[1])
        assertEquals(PermValue.No, rows.getValue("likes").values[0])
        assertEquals(PermValue.Text("每天 150 次+ & 更多"), rows.getValue("likes").values[1])
        assertEquals(PermValue.Text("全部"), rows.getValue("scope").values[1])
    }

    @Test
    fun freshMarksAndNotesBelongToTheirRow() {
        val scope = matrix.tiers.groups[0].rows[1]
        assertEquals("能看的帖子范围", scope.label)
        assertEquals("作者可以给帖子设阅读门槛", scope.note)
        assertEquals(mapOf(1 to "新"), scope.fresh)
        assertNull(matrix.tiers.groups[0].rows[0].note)
    }

    @Test
    fun theRoleTableIsTheSecondBlock() {
        val roles = requireNotNull(matrix.roles)
        assertEquals(listOf("成员"), roles.columns.map { it.name })
        assertEquals("你的身份", roles.columns[0].mineLabel)
        assertEquals(PermValue.Text("自己的主题"), roles.groups[0].rows[0].values[0])
    }

    @Test
    fun aPageWithoutTheMarkupIsNotAnEmptyTable() {
        assertNull(PermissionsHtml.parse("<html><body><p>维护中</p></body></html>"))
    }
}
