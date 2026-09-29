import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'

/**
 * 权限码契约漂移测试（前端所用权限码 ⊆ 后端 sys_menu.perms 定义）。
 *
 * 防止两类漂移：
 *   - 前端按钮 hasPerm('xxx') 用了后端从没定义的权限码 → 该按钮永远被隐藏/接口永远 403；
 *   - 后端改了/删了某权限码，前端仍在引用 → 这里立即变红。
 *
 * 后端权威集合来自 db/migration 下所有 Flyway 脚本里 sys_menu 的 perms 字段（框架种子 V2、
 * barrier 样例 V5…；它既驱动 @PreAuthorize，又通过 /auth/info 下发给前端 hasPermission），两边以它对齐。
 */
const FRONTEND_SRC = fileURLToPath(new URL('./', import.meta.url))
// 权限码分散在多个 Flyway 迁移（框架种子 V2、barrier 样例 V5…），扫描整个 db/migration 目录聚合
const MIGRATION_DIR = fileURLToPath(new URL('../../buddy/src/main/resources/db/migration', import.meta.url))

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) {
      walk(full, out)
    } else if (/\.(vue|ts)$/.test(entry.name) && !entry.name.endsWith('.spec.ts')) {
      out.push(full)
    }
  }
  return out
}

// 前端引用的权限码字面量：hasPerm('x') / hasPermission('x') / v-permission="'x'"
function usedPerms(): Set<string> {
  const found = new Set<string>()
  const patterns = [
    /(?:hasPerm|hasPermission)\(\s*['"]([^'"]+)['"]\s*\)/g,
    /(?:v-permission|v-auth)(?:\s*=\s*)?['"]\s*([^'"]+?)\s*['"]/g
  ]
  for (const file of walk(FRONTEND_SRC)) {
    const text = readFileSync(file, 'utf-8')
    for (const re of patterns) {
      let m: RegExpExecArray | null
      while ((m = re.exec(text)) !== null) {
        if (/:/.test(m[1])) found.add(m[1])
      }
    }
  }
  return found
}

// 后端定义的权限码全集：所有 Flyway 迁移里的 module:resource:action 三元组
function definedPerms(): Set<string> {
  const set = new Set<string>()
  // 兼容两段(notice:save)与三段(sys:user:list)权限码；要求首段以字母开头，
  // 避免把 'HH:mm:ss' 之类时间值误当成权限码
  const re = /'([a-zA-Z][\w]*(?::[\w]+)+)'/g
  for (const file of readdirSync(MIGRATION_DIR)) {
    if (!/^V\d+.*\.sql$/.test(file)) continue
    const sql = readFileSync(join(MIGRATION_DIR, file), 'utf-8')
    let m: RegExpExecArray | null
    re.lastIndex = 0
    while ((m = re.exec(sql)) !== null) {
      set.add(m[1])
    }
  }
  return set
}

describe('权限码契约：前端所用 ⊆ 后端 sys_menu.perms', () => {
  it('能扫描到前端权限码引用（否则说明检测失效）', () => {
    expect(usedPerms().size).toBeGreaterThan(0)
  })

  it('后端 data.sql 定义了权限码全集', () => {
    expect(definedPerms().size).toBeGreaterThan(10)
  })

  it('每个前端引用的权限码都在后端定义中存在（漂移即失败）', () => {
    const defined = definedPerms()
    const drift = [...usedPerms()].filter((p) => !defined.has(p))
    expect(drift, `前端引用了后端未定义的权限码: ${drift.join(', ')}`).toEqual([])
  })
})
