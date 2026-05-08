import React, { useEffect, useState } from 'react';
import { Tag, Tooltip, Button, Switch, Empty, Divider, Modal } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getSkillDetail, getSkillVersionTree } from '../../api';
import type { SkillRow, SkillDetailData } from './types';
import { CLOSE_ICON } from './icons';
import { SkillAbPanel } from './SkillAbPanel';
import { SkillEvolutionPanel } from './SkillEvolutionPanel';
import { EvalHistoryPanel } from './EvalHistoryPanel';
import { VersionTreeView } from './VersionTreeView';
import { SkillMdDiff } from './SkillMdDiff';

// Helper to ensure version strings always look like "vX.Y.Z"
const formatVer = (ver: string | number | undefined | null): string => {
  if (ver == null) return 'v0.0.0';
  const str = String(ver);
  return str.startsWith('v') ? str : `v${str}`;
};

interface SkillDrawerProps {
  skill: SkillRow;
  tab: string;
  setTab: (t: string) => void;
  onClose: () => void;
  onToggle: (id: number | string, enabled: boolean) => void;
  onDelete: (id: number | string) => void;
  currentUserId?: number;
  sourceAgentId: number | null;
  onOpenSkill?: (skillId: number) => void;
}

export const SkillDrawer: React.FC<SkillDrawerProps> = ({
  skill, tab, setTab, onClose, onToggle, onDelete, currentUserId, sourceAgentId, onOpenSkill,
}) => {
  const queryClient = useQueryClient();

  // State for version viewing and comparison
  const [viewingSkillId, setViewingSkillId] = useState<number>(skill.id as number);
  const [compareMode, setCompareMode] = useState(false);

  const { data: detail } = useQuery<SkillDetailData>({
    queryKey: ['skill-detail', viewingSkillId],
    queryFn: () => getSkillDetail(viewingSkillId).then(r => r.data),
    enabled: viewingSkillId != null,
  });

  // Auto-fallback: If the viewed version has no content, switch back to Live
  useEffect(() => {
    if (detail && !detail.skillMd && !detail.promptContent && viewingSkillId !== skill.id) {
      console.warn(`Version ${viewingSkillId} has no content. Falling back to Live version.`);
      setViewingSkillId(skill.id as number);
      message.warning('This version has no content. Switched to Live version.');
    }
  }, [detail, viewingSkillId, skill.id]);

  // Fetch base version for comparison (default to the previous version in tree or the live one)
  const baseSkillId = compareMode && viewingSkillId !== skill.id ? skill.id : null;
  const { data: baseDetail } = useQuery<SkillDetailData>({
    queryKey: ['skill-detail', baseSkillId],
    queryFn: () => getSkillDetail(baseSkillId!).then(r => r.data),
    enabled: !!baseSkillId,
  });

  useEffect(() => {
    setViewingSkillId(skill.id as number);
    setCompareMode(false);
  }, [skill.id]);

  const showAbPanel = !skill.system && typeof skill.id === 'number';

  // Tabs definition
  const tabs = [
    { id: 'readme', label: 'SKILL.md' },
    ...(showAbPanel ? [{ id: 'ab-test', label: 'A/B Test' }] : []),
    { id: 'eval-history', label: 'Eval History' },
  ];

  return (
    <>
      <div className="sf-drawer-backdrop" onClick={onClose} />
      <aside className="sf-drawer sf-drawer--wide" role="dialog" style={{ display: 'flex', flexDirection: 'column' }}>
        
        {/* Top Header */}
        <div className="sf-drawer-head" style={{ flexShrink: 0 }}>
          <div className="sf-drawer-head-row">
            <div>
              <h2 className="sf-drawer-title">{skill.name}</h2>
              {skill.description && <p className="sf-drawer-subtitle">{skill.description}</p>}
            </div>
            <button className="sf-icon-btn" onClick={onClose}>{CLOSE_ICON}</button>
          </div>
          
          <nav className="sf-drawer-tabs" style={{ marginTop: 12 }}>
            {tabs.map(t => (
              <button key={t.id} className={`sf-drawer-tab ${tab === t.id ? 'on' : ''}`} onClick={() => setTab(t.id)}>
                {t.label}
              </button>
            ))}
          </nav>
        </div>

        {/* Main Layout: Left Sidebar + Right Content */}
        <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
          
          {/* Left Sidebar: Version Navigation */}
          <div style={{ 
            width: 240, 
            borderRight: '1px solid var(--border-subtle)', 
            background: 'var(--bg-hover)', 
            overflowY: 'auto', 
            padding: '16px 0' 
          }}>
            <div style={{ padding: '0 16px', marginBottom: 12 }}>
              <h4 style={{ margin: 0, fontSize: 11, color: 'var(--fg-3)', letterSpacing: 1 }}>VERSIONS</h4>
            </div>
            {/* Hybrid View: Tree + Flat List of All Versions */}
            <VersionTreeView 
              skillId={skill.id as number} 
              userId={currentUserId || 0} 
              currentLiveId={skill.id as number}
              onView={(id) => setViewingSkillId(id)}
              onOpenSkill={onOpenSkill}
            />

            {/* Note: To show ALL versions including orphans, we would need to pass the full grouped list from the parent. 
                For now, the Tree shows the main evolution path. Orphaned versions can be managed from the main Skills List. */}
            <div style={{ margin: '16px 16px 0', borderTop: '1px solid var(--border-subtle)', paddingTop: 12 }}>
              <p style={{ fontSize: 10, color: 'var(--fg-4)', lineHeight: 1.5, margin: 0 }}>
                💡 <strong>Tip:</strong> Only linked evolutions are shown here. To manage parallel/orphaned versions with the same name, please use the main Skills List.
              </p>
            </div>
          </div>

          {/* Right Content Area */}
          <div style={{ flex: 1, overflowY: 'auto', padding: 24, background: 'var(--bg-surface)' }}>
            
            {tab === 'readme' && (
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
                  <h3 style={{ margin: 0, fontSize: 16 }}>SKILL.md Content</h3>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    <span style={{ fontSize: 12, color: 'var(--fg-3)' }}>Compare Versions</span>
                    <Switch checked={compareMode} onChange={setCompareMode} size="small" />
                    <Tag color={viewingSkillId === skill.id ? 'green' : 'default'}>
                      {viewingSkillId === skill.id ? 'Live Version' : `Viewing ${detail?.semver?.startsWith('v') ? detail.semver : `v${detail?.semver}`}`}
                    </Tag>
                    
                    {/* Delete Button for Candidate Versions */}
                    {viewingSkillId !== skill.id && (
                      <Button 
                        danger 
                        type="text"
                        size="small" 
                        onClick={() => {
                          const verName = detail?.semver?.startsWith('v') ? detail.semver : `v${detail?.semver}`;
                          Modal.confirm({
                            title: 'Delete this version?',
                            content: (
                              <div>
                                <p>Are you sure you want to delete <strong>{verName}</strong>?</p>
                                <p style={{ fontSize: 12, color: 'var(--fg-3)', marginTop: 8 }}>
                                  ⚠️ Note: This will only remove this specific version. The Live version ({formatVer(skill.semver)}) will remain unaffected.
                                </p>
                              </div>
                            ),
                            okText: 'Delete Version',
                            okButtonProps: { danger: true },
                            onOk: async () => {
                              try {
                                await onDelete(viewingSkillId);
                                message.success(`Version ${verName} deleted successfully.`);
                                
                                // Invalidate version tree cache to remove the deleted node from sidebar
                                queryClient.invalidateQueries({ queryKey: ['skill-version-tree', skill.id] });
                                
                                // Reset viewing to the live version after deletion
                                setViewingSkillId(skill.id as number);
                                setCompareMode(false);
                              } catch (error) {
                                message.error('Failed to delete version. Please try again.');
                              }
                            }
                          });
                        }}
                      >
                        Delete Version
                      </Button>
                    )}
                  </div>
                </div>

                {compareMode && baseDetail ? (
                  <div style={{ border: '1px solid var(--border-subtle)', borderRadius: 8, overflow: 'hidden' }}>
                    <SkillMdDiff 
                      parent={baseDetail.skillMd || ''} 
                      candidate={detail?.skillMd || ''} 
                    />
                  </div>
                ) : (
                  <div style={{ 
                    background: 'var(--bg-base)', 
                    borderRadius: 8, 
                    border: '1px solid var(--border-subtle)',
                    overflow: 'auto',
                    maxHeight: 'calc(100vh - 200px)'
                  }}>
                    <div style={{ padding: 20 }}>
                      {detail?.skillMd || detail?.promptContent ? (
                        <pre style={{ 
                          margin: 0, 
                          whiteSpace: 'pre-wrap', 
                          fontFamily: 'var(--font-mono, monospace)',
                          fontSize: 13,
                          lineHeight: 1.6
                        }}>
                          {detail.skillMd || detail.promptContent}
                        </pre>
                      ) : (
                        <Empty 
                          description={
                            <div style={{ textAlign: 'left' }}>
                              <p><strong>No SKILL.md found for this version.</strong></p>
                              <p style={{ fontSize: 12, color: 'var(--fg-3)' }}>
                                This version might have been archived, or the artifact file is missing on disk. 
                                Try switching to the <span style={{ color: 'var(--accent-primary)' }}>Live Version</span> to see the current content.
                              </p>
                            </div>
                          } 
                          image={Empty.PRESENTED_IMAGE_SIMPLE} 
                        />
                      )}
                    </div>
                  </div>
                )}
              </div>
            )}

            {tab === 'ab-test' && showAbPanel && (
              <div>
                <SkillAbPanel skillId={skill.id as number} agentId={sourceAgentId} skill={skill} />
                {sourceAgentId && <SkillEvolutionPanel skillId={skill.id as number} agentId={sourceAgentId} currentUserId={currentUserId} />}
              </div>
            )}

            {tab === 'eval-history' && typeof skill.id === 'number' && (
              <EvalHistoryPanel skillId={skill.id} currentUserId={currentUserId} agentId={sourceAgentId} />
            )}
          </div>
        </div>
      </aside>
    </>
  );
};
