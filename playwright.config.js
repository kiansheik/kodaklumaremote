import {defineConfig} from '@playwright/test';
export default defineConfig({
  testDir:'./tests/browser',
  timeout:20000,
  workers:1,
  reporter:'list',
  use:{baseURL:'http://127.0.0.1:4173',headless:true},
  webServer:{command:'npm run serve',url:'http://127.0.0.1:4173/watch/fixture/1/1',reuseExistingServer:false}
});
