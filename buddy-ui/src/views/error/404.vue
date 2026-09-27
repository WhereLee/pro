<template>
  <div class="not-found">
    <div class="code">404</div>
    <div class="desc">抱歉，你访问的页面不存在</div>
    <el-button type="primary" @click="$router.push('/')">返回首页</el-button>
  </div>
</template>

<style scoped>
.not-found {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
}

.code {
  font-size: 96px;
  font-weight: 700;
  color: #409eff;
  line-height: 1;
}

.desc {
  font-size: 15px;
  color: #909399;
}
</style>
