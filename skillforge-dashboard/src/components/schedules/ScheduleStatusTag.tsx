import React from 'react';
import { Tag } from 'antd';
import type {
  ScheduledTaskStatus,
  ScheduledTaskRunStatus,
} from '../../types/schedule';

/**
 * Color mapping per the FE brief's "状态展示" decision (idle 默认 / running 蓝 /
 * success 绿 / failure 红 / skipped 灰 / paused 橙). `completed` (one-shot
 * finished) maps to `success` styling so it pops as a positive terminal state.
 */
const TASK_STATUS_COLOR: Record<ScheduledTaskStatus, string> = {
  idle: 'default',
  running: 'blue',
  completed: 'green',
  error: 'red',
};

const RUN_STATUS_COLOR: Record<ScheduledTaskRunStatus, string> = {
  running: 'blue',
  success: 'green',
  failure: 'red',
  skipped: 'default',
  timeout: 'red',
  paused: 'orange',
};

export interface TaskStatusTagProps {
  status: ScheduledTaskStatus;
}

export const TaskStatusTag: React.FC<TaskStatusTagProps> = ({ status }) => (
  <Tag color={TASK_STATUS_COLOR[status]}>{status}</Tag>
);

export interface RunStatusTagProps {
  status: ScheduledTaskRunStatus;
}

export const RunStatusTag: React.FC<RunStatusTagProps> = ({ status }) => (
  <Tag color={RUN_STATUS_COLOR[status]}>{status}</Tag>
);
