# OnlyOffice 前端集成说明

## 概述

本文档说明如何在前端项目中集成 OnlyOffice 在线文档编辑器，用于企业子模板的在线编辑功能。

## 环境准备

### 1. 安装依赖

```bash
npm install @onlyoffice/document-editor-react
# 或
yarn add @onlyoffice/document-editor-react
```

### 2. 确认 OnlyOffice 服务

确保 OnlyOffice Document Server 已部署：
- 本地开发：`http://localhost:29090`
- 测试环境：`http://your-server:29090`

---

## 基础集成

### React 组件示例

```tsx
import React, { useEffect, useRef } from 'react';
import { DocumentEditor } from '@onlyoffice/document-editor-react';

interface OnlyOfficeEditorProps {
  templateId: string;
  documentUrl: string;
  fileName: string;
  mode?: 'edit' | 'view';
  onSave?: () => void;
  onError?: (error: any) => void;
}

export const OnlyOfficeEditor: React.FC<OnlyOfficeEditorProps> = ({
  templateId,
  documentUrl,
  fileName,
  mode = 'edit',
  onSave,
  onError
}) => {
  const editorRef = useRef<any>(null);

  // 生成文档唯一 key（用于缓存和协作）
  const documentKey = `${templateId}-${Date.now()}`;

  const config = {
    document: {
      fileType: 'docx',
      key: documentKey,
      url: documentUrl,
      title: fileName,
    },
    documentType: 'word',
    editorConfig: {
      mode: mode,
      lang: 'zh-CN',
      callbackUrl: `http://host.docker.internal:8080/api/company-template/${templateId}/onlyoffice-callback`,
      user: {
        id: 'user-001',
        name: '当前用户'
      },
      permissions: {
        edit: mode === 'edit',
        download: true,
        print: true,
        comment: true,
      },
      customization: {
        chat: false,
        comments: false,
        help: false,
        logo: {
          image: '',  // 可配置公司 logo
          url: ''
        },
        // 隐藏不需要的菜单
        hideRightMenu: false,
        hideLeftMenu: false,
        // 自动保存间隔（毫秒）
        autosave: true,
      }
    },
    events: {
      onReady: () => {
        console.log('OnlyOffice 编辑器加载完成');
      },
      onDocumentStateChange: (event: any) => {
        console.log('文档状态变化:', event.data);
      },
      onSaveCallback: (event: any) => {
        console.log('文档保存成功:', event.data);
        onSave?.();
      },
      onError: (event: any) => {
        console.error('编辑器错误:', event.data);
        onError?.(event.data);
      }
    }
  };

  return (
    <div style={{ height: '100vh', width: '100%' }}>
      <DocumentEditor
        id="doc-editor"
        documentServerUrl="http://localhost:29090"
        config={config}
        onLoadComponentError={(error) => {
          console.error('组件加载失败:', error);
          onError?.(error);
        }}
      />
    </div>
  );
};
```

### Vue 组件示例

```vue
<template>
  <div ref="editorContainer" style="height: 100vh; width: 100%;"></div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue';

const props = defineProps({
  templateId: String,
  documentUrl: String,
  fileName: String,
  mode: { type: String, default: 'edit' }
});

const emit = defineEmits(['save', 'error']);
const editorContainer = ref(null);
let docEditor = null;

onMounted(() => {
  // 加载 OnlyOffice API
  const script = document.createElement('script');
  script.src = 'http://localhost:29090/web-apps/apps/api/documents/api.js';
  script.onload = initEditor;
  document.head.appendChild(script);
});

onUnmounted(() => {
  if (docEditor) {
    docEditor.destroyEditor();
  }
});

const initEditor = () => {
  const config = {
    document: {
      fileType: 'docx',
      key: `${props.templateId}-${Date.now()}`,
      url: props.documentUrl,
      title: props.fileName,
    },
    documentType: 'word',
    editorConfig: {
      mode: props.mode,
      lang: 'zh-CN',
      callbackUrl: `http://host.docker.internal:8080/api/company-template/${props.templateId}/onlyoffice-callback`,
      user: {
        id: 'user-001',
        name: '当前用户'
      }
    },
    events: {
      onReady: () => console.log('编辑器就绪'),
      onSaveCallback: () => emit('save'),
      onError: (error) => emit('error', error)
    }
  };

  docEditor = new window.DocsAPI.DocEditor(editorContainer.value, config);
};
</script>
```

---

## 占位符功能集成

### 1. 获取占位符位置并高亮

```typescript
// 获取占位符位置信息
const fetchPlaceholderPositions = async (templateId: string) => {
  const response = await fetch(
    `/api/company-template/${templateId}/placeholder-positions`
  );
  const result = await response.json();
  return result.data || [];
};

// 高亮占位符（需要在 OnlyOffice 加载完成后调用）
const highlightPlaceholders = (docEditor: any, placeholders: any[]) => {
  placeholders.forEach(ph => {
    if (!ph.position) return;
    
    const { paragraphIndex, offset, elementType } = ph.position;
    
    // 使用 OnlyOffice API 高亮
    // 注意：实际 API 可能因版本不同有所差异
    docEditor.callCommand(() => {
      const oDocument = window.Asc.scope.oDocument;
      const oParagraph = oDocument.GetParagraphs()[paragraphIndex];
      
      if (oParagraph) {
        // 设置高亮样式
        oParagraph.SetHighlight(0, 255, 255); // 青色高亮
      }
    }, true);
  });
};

// 使用示例
useEffect(() => {
  if (editorRef.current) {
    fetchPlaceholderPositions(templateId).then(placeholders => {
      highlightPlaceholders(editorRef.current, placeholders);
      
      // 保存占位符数据供侧边栏使用
      setPlaceholderList(placeholders);
    });
  }
}, [templateId]);
```

### 2. 占位符侧边栏组件

```tsx
import React from 'react';

interface PlaceholderSidebarProps {
  placeholders: any[];
  onPlaceholderClick: (placeholder: any) => void;
  onConfirmPlaceholder?: (name: string, confirmed: boolean) => void;
}

export const PlaceholderSidebar: React.FC<PlaceholderSidebarProps> = ({
  placeholders,
  onPlaceholderClick,
  onConfirmPlaceholder
}) => {
  // 按状态分组
  const uncertainList = placeholders.filter(p => p.status === 'uncertain');
  const confirmedList = placeholders.filter(p => p.status === 'confirmed');
  const bvdList = placeholders.filter(p => 
    p.position?.elementType === 'bvd' || p.dataSource === 'bvd'
  );

  const renderPlaceholderItem = (ph: any) => (
    <div 
      key={ph.placeholderName}
      className={`placeholder-item ${ph.status}`}
      onClick={() => onPlaceholderClick(ph)}
    >
      <div className="placeholder-name">
        {ph.placeholderName}
        {ph.dataSource === 'bvd' && <span className="badge bvd">BVD</span>}
      </div>
      <div className="placeholder-status">
        {ph.status === 'uncertain' && (
          <input 
            type="checkbox" 
            onChange={(e) => onConfirmPlaceholder?.(ph.placeholderName, e.target.checked)}
          />
        )}
        <span className={`status-${ph.status}`}>
          {ph.status === 'confirmed' ? '✓' : '?'}
        </span>
      </div>
    </div>
  );

  return (
    <div className="placeholder-sidebar">
      <div className="sidebar-header">
        <h3>占位符列表</h3>
        <input type="text" placeholder="搜索占位符..." className="search-input" />
      </div>
      
      <div className="placeholder-groups">
        {uncertainList.length > 0 && (
          <div className="group">
            <div className="group-title">⚠️ 待确认 ({uncertainList.length})</div>
            {uncertainList.map(renderPlaceholderItem)}
          </div>
        )}
        
        {bvdList.length > 0 && (
          <div className="group">
            <div className="group-title">📊 BVD数据 ({bvdList.length})</div>
            {bvdList.map(renderPlaceholderItem)}
          </div>
        )}
        
        {confirmedList.length > 0 && (
          <div className="group">
            <div className="group-title">✓ 已确认 ({confirmedList.length})</div>
            {confirmedList.map(renderPlaceholderItem)}
          </div>
        )}
      </div>
    </div>
  );
};
```

---

## 未匹配长文本处理

### 1. 反向生成后检测

```typescript
// 反向生成完成后检查未匹配项
const handleReverseGenerate = async (formData: FormData) => {
  const response = await fetch('/api/company-template/reverse-generate', {
    method: 'POST',
    body: formData
  });
  const result = await response.json();
  
  if (result.data?.unmatchedLongTextEntries?.length > 0) {
    // 显示未匹配提示弹窗
    showUnmatchedModal(result.data.unmatchedLongTextEntries, result.data.template.id);
  } else {
    // 直接进入占位符确认页面
    navigate(`/template/${result.data.template.id}/confirm`);
  }
};
```

### 2. 未匹配提示弹窗

```tsx
interface UnmatchedModalProps {
  entries: any[];
  templateId: string;
  onIgnore: () => void;
  onGoToEditor: (placeholderName: string) => void;
}

export const UnmatchedModal: React.FC<UnmatchedModalProps> = ({
  entries,
  templateId,
  onIgnore,
  onGoToEditor
}) => {
  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <h3>⚠️ 以下长文本未自动匹配</h3>
        <p>这些内容在 Word 文档中未找到匹配位置，请手动检查：</p>
        
        <div className="unmatched-list">
          {entries.map((entry, index) => (
            <div key={index} className="unmatched-item">
              <div className="item-header">
                <span className="name">{entry.placeholderName}</span>
                <span className="source">{entry.sourceSheet}-{entry.sourceField}</span>
              </div>
              <div className="item-preview">
                {entry.value.substring(0, 100)}...
              </div>
              <div className="item-actions">
                <button onClick={() => onIgnore()}>忽略</button>
                <button 
                  className="primary"
                  onClick={() => onGoToEditor(entry.placeholderName)}
                >
                  去编辑器查找
                </button>
              </div>
            </div>
          ))}
        </div>
        
        <div className="modal-footer">
          <button onClick={onIgnore}>全部忽略</button>
        </div>
      </div>
    </div>
  );
};
```

### 3. 跳转到编辑器并定位

```typescript
// 跳转到 OnlyOffice 编辑器并定位
const goToEditorWithPosition = async (templateId: string, placeholderName: string) => {
  // 1. 获取占位符位置
  const positions = await fetchPlaceholderPositions(templateId);
  const targetPh = positions.find(p => p.placeholderName === placeholderName);
  
  if (!targetPh) {
    // 占位符未在文档中找到，提示用户手动查找
    message.warning('该占位符尚未在文档中替换，请手动查找');
  }
  
  // 2. 打开编辑器页面
  navigate(`/template/${templateId}/edit`, {
    state: { 
      highlightPlaceholder: placeholderName,
      position: targetPh?.position 
    }
  });
};
```

---

## 完整页面示例

```tsx
// TemplateEditPage.tsx
import React, { useEffect, useState } from 'react';
import { useParams, useLocation } from 'react-router-dom';
import { OnlyOfficeEditor } from './OnlyOfficeEditor';
import { PlaceholderSidebar } from './PlaceholderSidebar';

export const TemplateEditPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const location = useLocation();
  const [documentUrl, setDocumentUrl] = useState('');
  const [placeholders, setPlaceholders] = useState([]);
  const [loading, setLoading] = useState(true);
  
  // 从路由状态获取高亮信息
  const highlightInfo = location.state as { 
    highlightPlaceholder?: string;
    position?: any;
  };

  useEffect(() => {
    loadTemplate();
  }, [id]);

  const loadTemplate = async () => {
    setLoading(true);
    
    // 1. 获取文档 URL
    const urlRes = await fetch(`/api/company-template/${id}/content-url`);
    const urlData = await urlRes.json();
    setDocumentUrl(urlData.data.url);
    
    // 2. 获取占位符列表
    const phRes = await fetch(`/api/company-template/${id}/placeholder-positions`);
    const phData = await phRes.json();
    setPlaceholders(phData.data);
    
    setLoading(false);
  };

  const handlePlaceholderClick = (placeholder: any) => {
    // TODO: 调用 OnlyOffice API 跳转到对应位置
    console.log('跳转到占位符:', placeholder);
  };

  const handleConfirmPlaceholder = async (name: string, confirmed: boolean) => {
    await fetch(`/api/company-template/${id}/confirm-placeholders`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify([{
        placeholderName: name,
        confirmed: confirmed
      }])
    });
    
    // 刷新占位符列表
    loadTemplate();
  };

  if (loading) return <div>加载中...</div>;

  return (
    <div className="template-edit-page">
      <div className="editor-container">
        <OnlyOfficeEditor
          templateId={id!}
          documentUrl={documentUrl}
          fileName="企业子模板.docx"
          mode="edit"
          onSave={() => message.success('文档已保存')}
          onError={(err) => message.error('保存失败: ' + err)}
        />
      </div>
      
      <div className="sidebar-container">
        <PlaceholderSidebar
          placeholders={placeholders}
          onPlaceholderClick={handlePlaceholderClick}
          onConfirmPlaceholder={handleConfirmPlaceholder}
        />
      </div>
    </div>
  );
};
```

---

## 常见问题

### 1. 跨域问题 (CORS)

OnlyOffice 回调后端时可能遇到跨域问题，需要后端配置：

```java
// 已在 CompanyTemplateController 的 callback 接口处理
// 返回 Map 而非 R 对象，避免额外的包装
```

### 2. 文档无法加载

检查以下几点：
- `document.url` 必须可直接访问（浏览器能打开）
- 后端 `/api/company-template/{id}/content` 接口需要允许匿名访问或传递正确的 JWT
- 文档格式必须是 `docx`，不能是 `doc`

### 3. 回调地址问题

Windows Docker 环境使用 `host.docker.internal`：
```typescript
callbackUrl: 'http://host.docker.internal:8080/api/company-template/xxx/onlyoffice-callback'
```

Linux/Mac 环境使用 `localhost`：
```typescript
callbackUrl: 'http://localhost:8080/api/company-template/xxx/onlyoffice-callback'
```

### 4. 文档 Key 重复

`document.key` 必须是唯一的，建议：
```typescript
const documentKey = `${templateId}-${Date.now()}`;
```

---

## 参考文档

- [OnlyOffice API 文档](https://api.onlyoffice.com/editors/basic)
- [OnlyOffice React 组件](https://github.com/ONLYOFFICE/document-editor-react)
- [后端 API 文档](./前端配合需求-V4.md)

---

**文档版本**: V1  
**更新日期**: 2026-03-12
