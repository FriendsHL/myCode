import React, { useState, useCallback, useMemo } from 'react';
import { Button, Modal, Switch, Tooltip, Space, Tag, message, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Table } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import {
  listSchedules,
  deleteSchedule,
  triggerSchedule,
  updateSchedule,
} from '../api/schedules';
import { getAgents, extractList } from '../api';
import { useAuth } from '../contexts/AuthContext';
import type { ScheduledTask } from '../types/schedule';
import { TaskStatusTag } from '../components/schedules/ScheduleStatusTag';
import ScheduleEditDrawer from '../components/schedules/ScheduleEditDrawer';
import ScheduleRunHistoryDrawer from '../components/schedules/ScheduleRunHistoryDrawer';

const { Text } = Typography;

const PLUS_ICON = (
  <svg width={13} height={13} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
    <path d="M8 3v10M3 8h10" />
  </svg>
);

function fmtTime(iso: string | null): React.ReactNode {
  if (!iso) return <Text type="secondary">—</Text>;
  return (
    <Text style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>
      {dayjs(iso).format('YYYY-MM-DD HH:mm')}
    </Text>
  );
}

interface AgentLite {
  id: number;
  name: string;
}

const Schedules: React.FC = () => {
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const [editOpen, setEditOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<ScheduledTask | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyTarget, setHistoryTarget] = useState<ScheduledTask | null>(null);
  /**
   * Per-row trigger spinner. `useMutation`'s shared `isPending` would spin
   * EVERY row's "Run now" button when any one is firing — track the active
   * task id explicitly so only the clicked row shows a loader. Cleared in
   * `onSettled` (not `onSuccess`) so it also resets on error.
   */
  const [triggeringId, setTriggeringId] = useState<number | null>(null);

  const { data: tasks = [], isLoading } = useQuery({
    queryKey: ['schedules', userId],
    queryFn: () => listSchedules(userId).then((r) => r.data ?? []),
    staleTime: 15_000,
  });

  // Agent lookup so the table can render agent names; falls back to the
  // numeric id when the agent has been deleted (the BE keeps the FK as a
  // value column without ON DELETE SET NULL, so dangling ids are possible).
  const { data: agentsRaw = [] } = useQuery({
    queryKey: ['agents'],
    queryFn: () => getAgents().then((r) => extractList<Record<string, unknown>>(r)),
    staleTime: 60_000,
  });
  const agentMap = useMemo<Record<number, AgentLite>>(() => {
    const m: Record<number, AgentLite> = {};
    agentsRaw.forEach((a) => {
      const id = Number((a as Record<string, unknown>).id);
      const name = String((a as Record<string, unknown>).name || '');
      if (id) m[id] = { id, name };
    });
    return m;
  }, [agentsRaw]);

  const { mutate: removeTask } = useMutation({
    mutationFn: (id: number) => deleteSchedule(id, userId),
    onSuccess: () => {
      message.success('Schedule removed');
      queryClient.invalidateQueries({ queryKey: ['schedules'] });
    },
    onError: () => message.error('Failed to remove schedule'),
  });

  const { mutate: fireNow } = useMutation({
    mutationFn: (id: number) => triggerSchedule(id, userId),
    // Per-row spinner: set the active id on click, clear in `onSettled` so
    // the loader resets on both success and error.
    onMutate: (id: number) => {
      setTriggeringId(id);
    },
    onSuccess: () => {
      message.success('Triggered — see run history for status');
      queryClient.invalidateQueries({ queryKey: ['schedules'] });
    },
    onError: (e: unknown) => {
      const detail = e instanceof Error ? e.message : 'unknown';
      message.error(`Trigger failed: ${detail}`);
    },
    onSettled: () => {
      setTriggeringId(null);
    },
  });

  const { mutate: toggleEnabled } = useMutation({
    mutationFn: (vars: { id: number; enabled: boolean }) =>
      updateSchedule(vars.id, { enabled: vars.enabled }, userId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['schedules'] });
    },
    onError: () => message.error('Failed to toggle enabled'),
  });

  const handleOpenCreate = useCallback(() => {
    setEditTarget(null);
    setEditOpen(true);
  }, []);

  const handleOpenEdit = useCallback((task: ScheduledTask) => {
    setEditTarget(task);
    setEditOpen(true);
  }, []);

  const handleOpenHistory = useCallback((task: ScheduledTask) => {
    setHistoryTarget(task);
    setHistoryOpen(true);
  }, []);

  const handleDelete = useCallback(
    (task: ScheduledTask) => {
      Modal.confirm({
        title: `Remove "${task.name}"?`,
        content: 'This deletes the schedule and all its run history. This cannot be undone.',
        okText: 'Remove',
        okType: 'danger',
        onOk: () => removeTask(task.id),
      });
    },
    [removeTask],
  );

  const columns: ColumnsType<ScheduledTask> = [
    {
      title: 'Name',
      dataIndex: 'name',
      key: 'name',
      render: (name: string, task) => (
        <div>
          <div style={{ fontWeight: 600 }}>{name}</div>
          <Text type="secondary" style={{ fontSize: 11 }}>
            #{task.id}
          </Text>
        </div>
      ),
    },
    {
      title: 'Agent',
      dataIndex: 'agentId',
      key: 'agentId',
      width: 140,
      render: (agentId: number) => {
        const a = agentMap[agentId];
        return a ? a.name : <Text type="secondary">#{agentId} (missing)</Text>;
      },
    },
    {
      title: 'Trigger',
      key: 'trigger',
      width: 220,
      render: (_, task) => {
        if (task.cronExpr) {
          return (
            <Space size={4} direction="vertical">
              <Tag color="geekblue" style={{ marginRight: 0 }}>
                cron
              </Tag>
              <Text style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>
                {task.cronExpr}
              </Text>
              <Text type="secondary" style={{ fontSize: 11 }}>
                {task.timezone}
              </Text>
            </Space>
          );
        }
        if (task.oneShotAt) {
          return (
            <Space size={4} direction="vertical">
              <Tag color="purple" style={{ marginRight: 0 }}>
                one-shot
              </Tag>
              <Text style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>
                {dayjs(task.oneShotAt).format('YYYY-MM-DD HH:mm')}
              </Text>
            </Space>
          );
        }
        return <Text type="secondary">—</Text>;
      },
    },
    {
      title: 'Next fire',
      dataIndex: 'nextFireAt',
      key: 'nextFireAt',
      width: 150,
      render: (v: string | null) => fmtTime(v),
    },
    {
      title: 'Last fire',
      dataIndex: 'lastFireAt',
      key: 'lastFireAt',
      width: 150,
      render: (v: string | null) => fmtTime(v),
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: ScheduledTask['status']) => <TaskStatusTag status={status} />,
    },
    {
      title: 'Enabled',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 90,
      render: (enabled: boolean, task) => (
        <Switch
          checked={enabled}
          size="small"
          onChange={(checked) =>
            toggleEnabled({ id: task.id, enabled: checked })
          }
        />
      ),
    },
    {
      title: 'Actions',
      key: 'actions',
      width: 240,
      render: (_, task) => (
        <Space size="small">
          <Tooltip title="Edit">
            <Button size="small" onClick={() => handleOpenEdit(task)}>
              Edit
            </Button>
          </Tooltip>
          <Tooltip title="Manually trigger now (ignores enabled)">
            <Button
              size="small"
              loading={triggeringId === task.id}
              onClick={() => fireNow(task.id)}
            >
              Run now
            </Button>
          </Tooltip>
          <Tooltip title="View run history">
            <Button size="small" onClick={() => handleOpenHistory(task)}>
              History
            </Button>
          </Tooltip>
          <Tooltip title="Delete">
            <Button
              size="small"
              danger
              onClick={() => handleDelete(task)}
            >
              Delete
            </Button>
          </Tooltip>
        </Space>
      ),
    },
  ];

  return (
    <div style={{ padding: '24px 32px', maxWidth: 1400, margin: '0 auto' }}>
      <header
        style={{
          display: 'flex',
          alignItems: 'flex-end',
          justifyContent: 'space-between',
          marginBottom: 24,
          gap: 16,
        }}
      >
        <div>
          <h1 style={{ margin: 0, fontSize: 24, fontWeight: 600 }}>Scheduled Tasks</h1>
          <p style={{ margin: '4px 0 0', color: 'var(--fg-3)', fontSize: 13 }}>
            Cron-based or one-shot agent triggers. Auto-restored on app restart.
          </p>
        </div>
        <Button type="primary" icon={PLUS_ICON} onClick={handleOpenCreate}>
          New schedule
        </Button>
      </header>

      <Table<ScheduledTask>
        rowKey="id"
        columns={columns}
        dataSource={tasks}
        loading={isLoading}
        pagination={{ pageSize: 20 }}
        size="middle"
        locale={{
          emptyText: 'No schedules yet — create one to fire an agent on a cron or at a specific time.',
        }}
      />

      <ScheduleEditDrawer
        open={editOpen}
        task={editTarget}
        onClose={() => setEditOpen(false)}
      />

      <ScheduleRunHistoryDrawer
        open={historyOpen}
        task={historyTarget}
        onClose={() => setHistoryOpen(false)}
      />
    </div>
  );
};

export default Schedules;
