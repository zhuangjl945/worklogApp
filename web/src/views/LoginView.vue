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
    <div class="bg" />

    <div class="shell">
      <div class="brand">
        <div class="logo">W</div>
        <div class="brandText">
          <div class="brandName">Worklog</div>
          <div class="brandDesc">工作日志管理系统</div>
        </div>
      </div>

      <div class="card">
        <div class="title">登录</div>
        <div class="subtitle">请输入账号与密码继续</div>

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

        <div class="tips">建议使用 Chrome / Edge 浏览器获得更佳体验</div>
      </div>

      <div class="footer">Copyright © {{ new Date().getFullYear() }} Worklog</div>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  width: 100%;
  position: relative;
  overflow: hidden;
}

.bg {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(900px circle at 20% 10%, rgba(59, 130, 246, 0.28), transparent 55%),
    radial-gradient(700px circle at 90% 25%, rgba(168, 85, 247, 0.22), transparent 55%),
    radial-gradient(900px circle at 50% 90%, rgba(34, 197, 94, 0.16), transparent 55%),
    linear-gradient(180deg, #0b1020, #0b1020 40%, #0a0f1f);
}

.shell {
  min-height: 100vh;
  width: 100%;
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18px;
  padding: 28px 16px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  color: rgba(255, 255, 255, 0.92);
}

.logo {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  font-weight: 900;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.16);
  backdrop-filter: blur(10px);
}

.brandName {
  font-size: 18px;
  font-weight: 900;
  letter-spacing: 0.3px;
}

.brandDesc {
  margin-top: 2px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.70);
}

.card {
  width: min(420px, 92vw);
  background: rgba(255, 255, 255, 0.10);
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 16px;
  padding: 18px 18px 16px;
  box-shadow:
    0 30px 80px rgba(0, 0, 0, 0.45),
    0 1px 0 rgba(255, 255, 255, 0.05) inset;
  backdrop-filter: blur(14px);
}

.title {
  font-size: 18px;
  font-weight: 900;
  color: rgba(255, 255, 255, 0.95);
}

.subtitle {
  margin-top: 6px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.70);
  margin-bottom: 14px;
}

.error {
  margin: 10px 0 14px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(185, 28, 28, 0.22);
  border: 1px solid rgba(248, 113, 113, 0.28);
  color: #fecaca;
  font-size: 12px;
}

.submit {
  width: 100%;
  height: 40px;
  margin-top: 4px;
}

.tips {
  margin-top: 10px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.60);
}

.footer {
  text-align: center;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.55);
}
</style>
