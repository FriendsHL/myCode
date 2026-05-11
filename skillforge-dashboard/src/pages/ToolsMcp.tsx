import React, { useState } from 'react';
import ToolList from './ToolList';
import McpServers from './McpServers';
import './ToolsMcp.css';

type TabKey = 'tools' | 'mcp';

const ToolsMcp: React.FC = () => {
  const [activeTab, setActiveTab] = useState<TabKey>('tools');

  return (
    <div className="tools-mcp-page">
      <div className="tools-mcp-tabs">
        <button
          className={`tools-mcp-tab ${activeTab === 'tools' ? 'active' : ''}`}
          onClick={() => setActiveTab('tools')}
        >
          <span className="tools-mcp-tab-icon">🛠️</span>
          Tools
        </button>
        <button
          className={`tools-mcp-tab ${activeTab === 'mcp' ? 'active' : ''}`}
          onClick={() => setActiveTab('mcp')}
        >
          <span className="tools-mcp-tab-icon">🔌</span>
          MCP Servers
        </button>
      </div>

      <div className="tools-mcp-content">
        {activeTab === 'tools' && <ToolList />}
        {activeTab === 'mcp' && <McpServers />}
      </div>
    </div>
  );
};

export default ToolsMcp;
