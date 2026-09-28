package cl.erz.sailer.site

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * u-cursos.cl's own side menu (`#menu`: profile, share/reload, search,
 * contact, logout, and the Favoritos / current semester / Comunidades /
 * Instituciones lists), as read off a logged-in page by [EXTRACT_JS]. The
 * site's menu itself is hidden (see MainActivity) and MainActivity's native
 * drawer is built from this instead.
 *
 * Icons are either the site's image URLs (SVGs on static.u-cursos.cl, the
 * user's avatar) or, for rows the site draws with its icon font, a PNG data
 * URL of that glyph rendered by the page itself - see [Row.isGlyph].
 */
data class SiteMenu(
    val user: User?,
    val actions: List<Row>,
    val sections: List<Section>,
) {
    data class User(val name: String, val href: String, val avatar: String?)

    data class Section(val title: String, val rows: List<Row>)

    data class Row(
        val kind: String,
        val label: String,
        val sub: String,
        val href: String?,
        val icon: String?,
        /** [icon] is a white glyph mask, to be tinted like the app's own icons. */
        val isGlyph: Boolean,
        val isSelected: Boolean,
        val hint: String,
    )

    companion object {
        // Row kinds, as set by EXTRACT_JS.
        const val KIND_SHARE = "share"
        const val KIND_RELOAD = "reload"
        const val KIND_SEARCH = "search"
        const val KIND_LOGOUT = "logout"
        const val KIND_LINK = "link"

        private const val CACHE_FILE = "site_menu.json"

        fun parse(json: String): SiteMenu? = runCatching {
            val o = JSONObject(json)
            SiteMenu(
                user = o.optJSONObject("user")?.let {
                    User(it.getString("name"), it.getString("href"), it.optStringOrNull("avatar"))
                },
                actions = o.getJSONArray("actions").rows(),
                sections = o.getJSONArray("sections").let { a ->
                    (0 until a.length()).map { i ->
                        val s = a.getJSONObject(i)
                        Section(s.getString("title"), s.getJSONArray("rows").rows())
                    }
                },
            )
        }.getOrNull()

        private fun JSONArray.rows(): List<Row> = (0 until length()).map { i ->
            val r = getJSONObject(i)
            Row(
                kind = r.optString("kind", KIND_LINK),
                label = r.getString("label"),
                sub = r.optString("sub"),
                href = r.optStringOrNull("href"),
                icon = r.optStringOrNull("icon"),
                isGlyph = r.optBoolean("glyph"),
                isSelected = r.optBoolean("sel"),
                hint = r.optString("hint"),
            )
        }

        private fun JSONObject.optStringOrNull(name: String): String? =
            if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

        /** The last menu seen, so the drawer is filled in before the first page loads. */
        fun readCache(context: Context): String? =
            runCatching { File(context.cacheDir, CACHE_FILE).readText() }.getOrNull()

        fun writeCache(context: Context, json: String) {
            runCatching { File(context.cacheDir, CACHE_FILE).writeText(json) }
        }

        fun clearCache(context: Context) {
            File(context.cacheDir, CACHE_FILE).delete()
        }

        /**
         * Reads `#menu` and posts it as JSON through the `sailerBridge` object
         * MainActivity injects. Icon-font glyphs (a `::before` with the site's
         * Font Awesome) are drawn onto a canvas by the page, which already
         * has that font, once it's loaded - the menu is hidden, so it may not
         * be yet.
         */
        const val EXTRACT_JS = """
            (function() {
                var menu = document.getElementById('menu');
                if (!menu || !window.sailerBridge) return;
                var jobs = [];
                // Not String.trim(): the site overrides it with one that keeps trailing spaces.
                function text(el) { return el ? el.textContent.replace(/\s+/g, ' ').replace(/^ | $/g, '') : ''; }
                function glyphOf(el) {
                    var els = el ? [el].concat([].slice.call(el.querySelectorAll('*'))) : [];
                    for (var i = 0; i < els.length; i++) {
                        var s = getComputedStyle(els[i], '::before');
                        var c = (s.content || '').replace(/^["']|["']$/g, '');
                        if (c && c !== 'none' && c !== 'normal') return { c: c, font: s.fontStyle + ' ' + s.fontWeight + ' 96px ' + s.fontFamily };
                    }
                    return null;
                }
                function render(g) {
                    return document.fonts.load(g.font, g.c).catch(function() {}).then(function() {
                        var cv = document.createElement('canvas');
                        cv.width = cv.height = 128;
                        var ctx = cv.getContext('2d');
                        ctx.font = g.font;
                        ctx.fillStyle = '#fff';
                        var m = ctx.measureText(g.c);
                        ctx.fillText(g.c,
                            64 - (m.actualBoundingBoxRight - m.actualBoundingBoxLeft) / 2,
                            64 + (m.actualBoundingBoxAscent - m.actualBoundingBoxDescent) / 2);
                        return cv.toDataURL('image/png');
                    });
                }
                function row(kind, label, href, glyphEl, img, extra) {
                    var r = { kind: kind, label: label, href: href || null, icon: img ? img.src : null, glyph: false };
                    for (var k in extra) r[k] = extra[k];
                    var g = !r.icon && glyphOf(glyphEl);
                    if (g) jobs.push(render(g).then(function(d) { r.icon = d; r.glyph = true; }));
                    return r;
                }
                var out = { user: null, actions: [], sections: [] };
                var p = menu.querySelector('#widget_perfil a');
                if (p) {
                    var av = p.querySelector('img');
                    out.user = { name: text(p), href: p.href, avatar: av ? av.src : null };
                }
                [].forEach.call(menu.querySelectorAll('.pwa a'), function(a) {
                    var kind = a.classList.contains('permalink') ? 'share' : 'reload';
                    out.actions.push(row(kind, text(a.querySelector('.tooltip')) || text(a), a.href, a.querySelector('i')));
                });
                var f = menu.querySelector('#widget_buscador form');
                if (f) {
                    var q = f.querySelector('input[name=q]'), b = f.querySelector('button');
                    out.actions.push(row('search', text(b) || (q && q.title) || '', f.action, b, null, { hint: q ? q.placeholder : '' }));
                }
                [['li.contact a', 'link'], ['li.logout a', 'logout']].forEach(function(s) {
                    var a = menu.querySelector(s[0]);
                    if (a) out.actions.push(row(s[1], text(a), a.href, a));
                });
                [].forEach.call(menu.querySelectorAll('#modulos > div'), function(d) {
                    var rows = [].map.call(d.querySelectorAll(':scope > ul > li > a'), function(a) {
                        var h = a.querySelector('h1');
                        return row('link', text(h && h.querySelector('span')) || text(h), a.href, null, h && h.querySelector('img'),
                            { sub: text(a.querySelector('h2')), sel: a.parentNode.classList.contains('sel') });
                    });
                    if (rows.length) out.sections.push({ title: text(d.querySelector(':scope > h1')), rows: rows });
                });
                Promise.all(jobs).then(function() { sailerBridge.postMessage(JSON.stringify(out)); });
            })();
        """
    }
}
