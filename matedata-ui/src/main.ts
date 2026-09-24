import { createApp } from "vue";
import { uiComponents } from "./uiComponents";
import "element-plus/dist/index.css";
import App from "./App.vue";
import "./style.css";
createApp(App).use(uiComponents).mount("#app");
