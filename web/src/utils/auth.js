import { computed, ref } from 'vue'
import { me } from '../api/auth'
import { permissionList } from '../api/permission'

/* ============================================================
   角色体系前端收口
   ------------------------------------------------------------
   角色值与鉴权判定全部以后端为准（Role 枚举 + @RequireRole + DataScope），
   这里只做两件事：缓存 /auth/me 的结果、把「该给谁看什么」算成布尔值。
   注意：前端隐藏入口只是体验优化，真正的门槛在服务端，别把它当安全边界。
   ============================================================ */

// 与后端 Role 一一对应；顺序即权限递增（USER < DEPT_ADMIN < ADMIN）
export const ROLE = {
  USER: 'USER',
  DEPT_ADMIN: 'DEPT_ADMIN',
  ADMIN: 'ADMIN'
}

const ROLE_LEVEL = { USER: 0, DEPT_ADMIN: 1, ADMIN: 2 }

// 兜底中文名：后端已返回 roleLabel，这里只在字段缺失时用
const ROLE_FALLBACK_LABEL = {
  USER: '普通员工',
  DEPT_ADMIN: '科室管理员',
  ADMIN: '系统管理员'
}

/** /auth/me 的结果；null 表示尚未加载 */
const profile = ref(null)
/** 缓存归属的 token：换账号（含退出）后指纹不符会自动重新拉取，省掉手动清理 */
let cachedToken = null

function tokenNow() {
  return localStorage.getItem('access_token') || ''
}

/**
 * 权限点门槛：{ 'board.scopeAll': 'ADMIN', ... }
 * 由后端 /api/permissions 下发，和接口鉴权用的是同一份配置，不存在两边算不一致。
 */
const permissions = ref({})
let permToken = null

/** 退出登录时清空，避免下一个账号看到上一个账号的菜单 */
export function clearProfile() {
  profile.value = null
  cachedToken = null
  permissions.value = {}
  permToken = null
}

/**
 * 确保拿到当前登录者的档案。
 *
 * <p>失败时按「未登录」处理返回 null：角色体系一律走最小权限，
 * 绝不在拿不到角色时放行管理员入口。
 */
export async function ensureProfile(force = false) {
  const token = tokenNow()
  if (!token) {
    clearProfile()
    return null
  }
  if (!force && cachedToken === token && profile.value) {
    return profile.value
  }
  try {
    const resp = await me()
    profile.value = resp.data || null
    cachedToken = token
    return profile.value
  } catch (e) {
    clearProfile()
    return null
  }
}

/** 同步读取档案（可能为 null）；页面需要 id/科室时用这个，配合 ensureProfile 预热 */
export function currentProfile() {
  return profile.value
}

/**
 * 拉取权限点门槛。
 *
 * <p>失败时返回 false 并保留旧值：菜单/按钮会退回各自声明的内置下限（roles 字段），
 * 也就是「配置读不到时按代码里的下限渲染」，不会因为一次请求失败就把入口全藏掉。
 */
export async function ensurePermissions(force = false) {
  const token = tokenNow()
  if (!token) {
    permissions.value = {}
    permToken = null
    return false
  }
  if (!force && permToken === token && Object.keys(permissions.value).length > 0) {
    return true
  }
  try {
    const resp = await permissionList()
    const map = {}
    for (const item of resp.data?.items || []) {
      map[item.key] = item.minRole
    }
    permissions.value = map
    permToken = token
    return true
  } catch (e) {
    return false
  }
}

/** 保存权限后由权限设置页调用，让菜单立刻跟上 */
export function setPermissions(map) {
  permissions.value = { ...map }
  permToken = tokenNow()
}

/** 某个权限点当前要求的最低角色（未加载/未知时返回 null） */
export function permissionMinRole(key) {
  return permissions.value[key] || null
}

/** 生效角色：档案未加载或字段缺失一律按 USER，和后端 Role.of(null) 的兜底保持一致 */
export function currentRole() {
  return profile.value?.role || ROLE.USER
}

/** 「不低于」某个角色，等价于后端 @RequireRole 的判定方式 */
export function atLeast(target) {
  return (ROLE_LEVEL[currentRole()] ?? 0) >= (ROLE_LEVEL[target] ?? 0)
}

/** 路由 meta.roles / 菜单 roles 用的判定：命中任一「最低角色」即放行 */
export function allowedBy(roles) {
  if (!roles || roles.length === 0) return true
  return roles.some((r) => atLeast(r))
}

/**
 * 权限点判定：优先用后端下发的门槛，拿不到时退回调用方给的内置下限。
 *
 * <p>为什么要有 fallback：配置接口偶发失败时，如果一律按「未知即拒绝」处理，
 * 管理员会看到整个菜单塌掉，比按内置下限渲染更难排查。
 * fallback 传的就是代码里那个下限（菜单项/路由上的 roles 字段），两边语义一致。
 */
export function can(key, fallbackRoles) {
  const min = key ? permissions.value[key] : null
  if (min) return atLeast(min)
  return allowedBy(fallbackRoles)
}

export const roleLabel = computed(() => {
  const p = profile.value
  return p?.roleLabel || ROLE_FALLBACK_LABEL[currentRole()] || ''
})

/** 这个角色本身够不够看他人数据（不含权限点配置）；判定功能开关请用 can(key) */
export const canViewDept = computed(() => atLeast(ROLE.DEPT_ADMIN))

/**
 * 编辑他人记录所需的最低角色：读 record.editDeptOthers 权限点，读不到退回内置下限。
 * 与后端 DataScope.canEdit 的入参同源，改后端记得同步这里。
 */
export const deptEditMinRole = computed(() => permissions.value['record.editDeptOthers'] || ROLE.DEPT_ADMIN)

/**
 * 这条记录他能不能改。
 *
 * <p>删除不走这个判定——后端刻意只允许本人删除，界面上别拿它当删除权限用。
 */
export function canEditRecord(record) {
  const p = profile.value
  if (!p || !record) return false
  if (record.userId === p.id) return true
  if (p.role === ROLE.ADMIN) return true
  // dept_id 为 0 是历史兜底值，不能当成有效科室
  return atLeast(deptEditMinRole.value) && !!record.deptId && record.deptId === p.deptId
}