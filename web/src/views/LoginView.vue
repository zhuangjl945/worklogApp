<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '../api/auth'

const router = useRouter()
const route = useRoute()

const formRef = ref()
const form = reactive({
  username: '',
  password: ''
})

const loading = ref(false)
const errorMsg = ref('')

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function onSubmit() {
  errorMsg.value = ''
  await formRef.value?.validate?.(async (valid) => {
    if (!valid) return

    loading.value = true
    try {
      const resp = await login(form.username, form.password)

      const auth = `${resp.data.tokenType || 'Bearer'} ${resp.data.token}`
      localStorage.setItem('access_token', auth)

      const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/home'
      await router.replace(redirect)
    } catch (e) {
      errorMsg.value = e?.message || '登录失败'
    } finally {
      loading.value = false
    }
  })
}
</script>

<template>
  <div class="page">
    <div class="container">
      <!-- 左侧品牌区 -->
      <div class="brand">
        <div class="brandInner">
          <div class="logoMark">W</div>
          <h1 class="brandTitle">Worklog</h1>
          <p class="brandDesc">工作日志管理系统</p>
          <div class="brandFeatures">
            <div class="feature">
              <span class="featureDot"></span>
              <span>工作记录与分类统计</span>
            </div>
            <div class="feature">
              <span class="featureDot"></span>
              <span>问题受理与工单流转</span>
            </div>
            <div class="feature">
              <span class="featureDot"></span>
              <span>合同管理与付款计划</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧登录表单 -->
      <div class="formSide">
        <div class="formCard">
          <h2 class="formTitle">登录</h2>
          <p class="formSubtitle">请输入账号与密码继续</p>

          <div v-if="errorMsg" class="error">{{ errorMsg }}</div>

          <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large" @submit.prevent>
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" placeholder="请输入用户名" autocomplete="username" />
            </el-form-item>

            <el-form-item label="密码" prop="password">
              <el-input
                v-model="form.password"
                placeholder="请输入密码"
                type="password"
                show-password
                autocomplete="current-password"
                @keyup.enter="onSubmit"
              />
            </el-form-item>

            <el-button class="submit" type="primary" :loading="loading" @click="onSubmit">登录</el-button>
          </el-form>

          <div class="tips">建议使用 Chrome / Edge 浏览器</div>
        </div>

        <div class="footer">© {{ new Date().getFullYear() }} Worklog</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  width: 100%;
  background: var(--g-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.container {
  display: flex;
  width: min(920px, 100%);
  min-height: 520px;
  border: 1px solid var(--g-border);
  border-radius: var(--g-radius-lg);
  overflow: hidden;
  box-shadow: var(--g-shadow);
}

/* 左侧品牌区 */
.brand {
  width: 380px;
  flex-shrink: 0;
  background: var(--g-text);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 40px;
}

.brandInner {
  max-width: 280px;
}

.logoMark {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: rgba(255,255,255,0.15);
  border: 1px solid rgba(255,255,255,0.2);
  display: grid;
  place-items: center;
  font-weight: 900;
  font-size: 22px;
  margin-bottom: 24px;
}

.brandTitle {
  font-size: 28px;
  font-weight: 900;
  letter-spacing: -0.04em;
  margin: 0 0 6px;
}

.brandDesc {
  font-size: 14px;
  color: rgba(255,255,255,0.6);
  margin: 0 0 40px;
}

.brandFeatures {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.feature {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: rgba(255,255,255,0.75);
}

.featureDot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: rgba(255,255,255,0.4);
  flex-shrink: 0;
}

/* 右侧表单 */
.formSide {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 40px;
  background: var(--g-bg);
}

.formCard {
  width: min(360px, 100%);
}

.formTitle {
  font-size: 22px;
  font-weight: 800;
  color: var(--g-text);
  letter-spacing: -0.02em;
  margin: 0 0 4px;
}

.formSubtitle {
  font-size: 13px;
  color: var(--g-text-muted);
  margin: 0 0 28px;
}

.error {
  margin: 0 0 16px;
  padding: 10px 14px;
  border-radius: var(--g-radius);
  background: var(--g-danger-bg);
  border: 1px solid #fecaca;
  color: var(--g-danger);
  font-size: 13px;
  font-weight: 500;
}

.submit {
  width: 100%;
  height: 42px;
  margin-top: 4px;
  font-size: 14px;
}

.tips {
  margin-top: 20px;
  font-size: 12px;
  color: var(--g-text-faint);
  text-align: center;
}

.footer {
  margin-top: 32px;
  font-size: 12px;
  color: var(--g-text-faint);
}

/* 响应式 */
@media (max-width: 720px) {
  .container {
    flex-direction: column;
    min-height: auto;
  }
  .brand {
    width: 100%;
    padding: 32px 24px;
  }
  .brandFeatures {
    display: none;
  }
  .formSide {
    padding: 32px 24px;
  }
}
</style>
