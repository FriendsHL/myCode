import React from 'react';
import { Drawer, Table, Empty, Tooltip, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import dayjs from 'dayjs';
import { listScheduleRuns } from '../../api/schedules';
import { useAuth } from '../../contexts/AuthContext';
import type { ScheduledTask, ScheduledTaskRun } from '../../types/schedule';
import { RunStatusTag } from './ScheduleStatusTag';

const { Text } = Typography;

export interface ScheduleRunHistoryDrawerProps {
  open: boolean;
  task: ScheduledTask | null;
  onClose: () => void;
}

function formatDuration(triggeredAt: string, finishedAt: string | null): string {
  if (!finishedAt) return 'in-flight';
  const ms = dayjs(finishedAt).diff(dayjs(triggeredAt), 'millisecond');
  if (ms >= 60000) return `${(ms / 60000).toFixed(1)}m`;
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)}s`;
  return `${ms}ms`;
}

const ScheduleRunHistoryDrawer: React.FC<ScheduleRunHistoryDrawerProps> = ({
  open,
  task,
  onClose,
}) => {
  const { userId } = useAuth();
  const { data: runs = [], isLoading } = useQuery({
    queryKey: ['schedule-runs', task?.id, userId],
    queryFn: () =>
      task
        ? listScheduleRuns(task.id, userId, { limit: 50 }).then((r) => r.data ?? [])
        : Promise.resolve<ScheduledTaskRun[]>([]),
    enabled: open && task !== null,
  });

  const columns: ColumnsType<ScheduledTaskRun> = [
    {
      title: 'Triggered at',
      dataIndex: 'triggeredAt',
      key: 'triggeredAt',
      width: 170,
      render: (v: string) => (
        <Text style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>
          {dayjs(v).format('YYYY-MM-DD HH:mm:ss')}
        </Text>
      ),
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: ScheduledTaskRun['status']) => <RunStatusTag status={status} />,
    },
    {
      title: 'Duration',
      key: 'duration',
      width: 90,
      render: (_, run) => formatDuration(run.triggeredAt, run.finishedAt),
    },
    {
      title: 'Trigger',
      dataIndex: 'manual',
      key: 'manual',
      width: 80,
      render: (manual: boolean) => (manual ? 'manual' : 'scheduled'),
    },
    {
      title: 'Session',
      dataIndex: 'triggeredSessionId',
      key: 'triggeredSessionId',
      render: (sid: string | null) =>
        sid ? (
          // V3 dogfood 2026-05-15: link to /chat/<sid> instead of /sessions/<sid>
          // so the operator can resume a paused (ask_user) session in-context,
          // not just view the transcript read-only.
          <Link to={`/chat/${sid}`} style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}>
            {sid.slice(0, 8)}…
          </Link>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
    {
      title: 'Error',
      dataIndex: 'errorMessage',
      key: 'errorMessage',
      render: (msg: string | null) =>
        msg ? (
          <Tooltip title={msg}>
            <Text
              type="danger"
              ellipsis
              style={{ fontSize: 12, maxWidth: 220, display: 'inline-block' }}
            >
              {msg}
            </Text>
          </Tooltip>
        ) : (
          <Text type="secondary">—</Text>
        ),
    },
  ];

  return (
    <Drawer
      title={task ? `Run history: ${task.name}` : 'Run history'}
      open={open}
      onClose={onClose}
      width={780}
      destroyOnClose
    >
      {!task ? (
        <Empty description="No task selected" />
      ) : (
        <Table<ScheduledTaskRun>
          rowKey="id"
          columns={columns}
          dataSource={runs}
          loading={isLoading}
          pagination={{ pageSize: 20, showSizeChanger: false }}
          size="small"
          locale={{ emptyText: 'No runs yet' }}
        />
      )}
    </Drawer>
  );
};

export default ScheduleRunHistoryDrawer;
