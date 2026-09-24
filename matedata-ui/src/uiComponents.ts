import type { Plugin } from "vue";
import {
  ElAlert,
  ElButton,
  ElCollapse,
  ElCollapseItem,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElInputNumber,
  ElOption,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElSkeleton,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElTabs,
  ElTag,
  ElTimeline,
  ElTimelineItem,
} from "element-plus";
export const uiComponents: Plugin = {
  install(app) {
    for (const component of [
      ElAlert,
      ElButton,
      ElCollapse,
      ElCollapseItem,
      ElDialog,
      ElEmpty,
      ElForm,
      ElFormItem,
      ElIcon,
      ElInput,
      ElInputNumber,
      ElOption,
      ElRadioButton,
      ElRadioGroup,
      ElSelect,
      ElSkeleton,
      ElSwitch,
      ElTable,
      ElTableColumn,
      ElTabPane,
      ElTabs,
      ElTag,
      ElTimeline,
      ElTimelineItem,
    ]) {
      app.use(component);
      // Some Element Plus child exports intentionally have a no-op install method.
      // Register a selected standalone child when its parent installer is not included.
      if (component.name && !app.component(component.name))
        app.component(component.name, component);
    }
  },
};
