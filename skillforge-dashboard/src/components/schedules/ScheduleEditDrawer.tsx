import React, { useEffect, useMemo, useState } from 'react';
import {
  Drawer,
  Form,
  Input,
  Select,
  Switch,
  Button,
  Space,
  Radio,
  DatePicker,
  Divider,
  Alert,
  message,
} from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs, { Dayjs } from 'dayjs';
import { getAgents, extractList } from '../../api';
import { useAuth } from '../../contexts/AuthContext';
import { createSchedule, updateSchedule } from '../../api/schedules';
import type {
  ScheduledTask,
  CreateScheduledTaskRequest,
  UpdateScheduledTaskRequest,
  ScheduleTriggerKind,
  ScheduleSessionMode,
  ChannelTarget,
} from '../../types/schedule';

const { TextArea } = Input;

/**
 * Common IANA timezones surfaced as a quick-pick. Users can still type any
 * other tz; the BE re-validates with `TimeZone.getTimeZone(...).getID()`
 * (PRD §10 risk on DST/timezone) and falls back to `Asia/Shanghai` for
 * unknown values, so the FE doesn't need a complete picker.
 */
const TIMEZONE_OPTIONS = [
  { label: 'Asia/Shanghai (UTC+8)', value: 'Asia/Shanghai' },
  { label: 'UTC', value: 'UTC' },
  { label: 'America/Los_Angeles', value: 'America/Los_Angeles' },
  { label: 'America/New_York', value: 'America/New_York' },
  { label: 'Europe/London', value: 'Europe/London' },
];

/**
 * Channel platforms shipped by SkillForge today (mirrors `/api/channels`
 * platforms). `''` represents "no channel push" — when the user selects it
 * the BE receives `channelTarget: null`.
 */
const CHANNEL_TYPE_OPTIONS = [
  { label: 'No channel push', value: '' },
  { label: '飞书 (Feishu)', value: 'feishu' },
  { label: 'Telegram', value: 'telegram' },
];

interface AgentOption {
  id: number;
  name: string;
}

function normalizeAgents(raw: unknown[]): AgentOption[] {
  return raw.map((r) => {
    const a = r as Record<string, unknown>;
    return { id: Number(a.id), name: String(a.name || '') };
  });
}

/**
 * Internal form shape. The drawer flattens the wire JSON's nested shapes
 * (`channelTarget` object, `oneShotAt` ISO string) into discrete antd
 * Form fields and re-assembles them on submit.
 */
interface FormValues {
  name: string;
  agentId: number;
  triggerKind: ScheduleTriggerKind;
  cronExpr?: string;
  oneShotAt?: Dayjs;
  timezone: string;
  promptTemplate: string;
  sessionMode: ScheduleSessionMode;
  channelType: string;
  channelId?: string;
  enabled: boolean;
}

export interface ScheduleEditDrawerProps {
  open: boolean;
  task: ScheduledTask | null;
  onClose: () => void;
}

const ScheduleEditDrawer: React.FC<ScheduleEditDrawerProps> = ({
  open,
  task,
  onClose,
}) => {
  const [form] = Form.useForm<FormValues>();
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const isEdit = task !== null;
  const [triggerKind, setTriggerKind] = useState<ScheduleTriggerKind>('cron');
  const [channelType, setChannelType] = useState<string>('');

  const { data: agentsRaw = [] } = useQuery({
    queryKey: ['agents'],
    queryFn: () => getAgents().then((r) => extractList<Record<string, unknown>>(r)),
  });
  const agents = useMemo(() => normalizeAgents(agentsRaw), [agentsRaw]);

  // ─── Initialize / reset form on open ─────────────────────────────────────
  useEffect(() => {
    if (!open) return;
    if (task) {
      const kind: ScheduleTriggerKind = task.cronExpr ? 'cron' : 'one-shot';
      const ch = task.channelTarget;
      form.setFieldsValue({
        name: task.name,
        agentId: task.agentId,
        triggerKind: kind,
        cronExpr: task.cronExpr ?? undefined,
        oneShotAt: task.oneShotAt ? dayjs(task.oneShotAt) : undefined,
        timezone: task.timezone,
        promptTemplate: task.promptTemplate,
        sessionMode: task.sessionMode,
        channelType: ch?.channelType ?? '',
        channelId: ch?.channelId ?? undefined,
        enabled: task.enabled,
      });
      setTriggerKind(kind);
      setChannelType(ch?.channelType ?? '');
    } else {
      form.resetFields();
      form.setFieldsValue({
        triggerKind: 'cron',
        timezone: 'Asia/Shanghai',
        sessionMode: 'new',
        channelType: '',
        enabled: true,
      });
      setTriggerKind('cron');
      setChannelType('');
    }
  }, [open, task, form]);

  // ─── Save mutation ───────────────────────────────────────────────────────
  const { mutate: save, isPending } = useMutation({
    mutationFn: async (values: FormValues) => {
      // Mutual-exclusion enforcement: explicit `null` clears the other column
      // server-side, supporting cron ↔ one-shot interconversion (INV-3).
      const cronExpr = values.triggerKind === 'cron' ? (values.cronExpr ?? '').trim() : null;
      const oneShotAt =
        values.triggerKind === 'one-shot' && values.oneShotAt
          ? values.oneShotAt.toISOString()
          : null;

      const channelTarget: ChannelTarget | null =
        values.channelType && values.channelId
          ? { channelType: values.channelType, channelId: values.channelId.trim() }
          : null;

      if (isEdit && task) {
        const body: UpdateScheduledTaskRequest = {
          name: values.name,
          agentId: values.agentId,
          cronExpr,
          oneShotAt,
          timezone: values.timezone,
          promptTemplate: values.promptTemplate,
          sessionMode: values.sessionMode,
          channelTarget,
          enabled: values.enabled,
        };
        return updateSchedule(task.id, body, userId);
      } else {
        const body: CreateScheduledTaskRequest = {
          name: values.name,
          agentId: values.agentId,
          cronExpr,
          oneShotAt,
          timezone: values.timezone,
          promptTemplate: values.promptTemplate,
          sessionMode: values.sessionMode,
          channelTarget,
          enabled: values.enabled,
        };
        return createSchedule(body, userId);
      }
    },
    onSuccess: () => {
      message.success(isEdit ? 'Schedule updated' : 'Schedule created');
      queryClient.invalidateQueries({ queryKey: ['schedules'] });
      onClose();
    },
    onError: (e: unknown) => {
      const detail = e instanceof Error ? e.message : 'unknown error';
      message.error(`Save failed: ${detail}`);
    },
  });

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      // Cross-field invariant: exactly one of cronExpr/oneShotAt is set.
      // The Form.Item rules already require the active field, so we get here
      // only when both UI states are valid; double-check defensively.
      if (values.triggerKind === 'cron' && !values.cronExpr?.trim()) {
        message.error('Cron expression is required');
        return;
      }
      if (values.triggerKind === 'one-shot' && !values.oneShotAt) {
        message.error('One-shot time is required');
        return;
      }
      save(values);
    } catch {
      // antd Form surfaces validation errors inline — nothing to do here.
    }
  };

  // ─── Display: next fire preview (BE-computed nextFireAt) ─────────────────
  // MVP per brief: don't ship cronstrue for humanization; show raw cron expr
  // + the BE-supplied `nextFireAt` after first save. V2 can add cronstrue.
  const nextFireDisplay = task?.nextFireAt
    ? dayjs(task.nextFireAt).format('YYYY-MM-DD HH:mm:ss Z')
    : '—';

  return (
    <Drawer
      title={isEdit ? `Edit ${task?.name ?? 'Schedule'}` : 'New Schedule'}
      open={open}
      onClose={onClose}
      width={520}
      destroyOnClose
      footer={
        <Space style={{ justifyContent: 'flex-end', width: '100%' }}>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="primary" loading={isPending} onClick={handleSubmit}>
            {isEdit ? 'Save' : 'Create'}
          </Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical" requiredMark="optional">
        <Form.Item
          label="Name"
          name="name"
          rules={[{ required: true, message: 'Name is required' }, { max: 128 }]}
        >
          <Input placeholder="e.g. Daily standup digest" />
        </Form.Item>

        <Form.Item
          label="Agent"
          name="agentId"
          rules={[{ required: true, message: 'Select an agent' }]}
        >
          <Select
            placeholder="Select agent"
            options={agents.map((a) => ({ value: a.id, label: a.name }))}
            showSearch
            filterOption={(input, opt) =>
              String(opt?.label ?? '').toLowerCase().includes(input.toLowerCase())
            }
          />
        </Form.Item>

        <Divider style={{ margin: '8px 0 16px' }} />

        <Form.Item label="Trigger" name="triggerKind">
          <Radio.Group
            onChange={(e) => setTriggerKind(e.target.value as ScheduleTriggerKind)}
          >
            <Radio.Button value="cron">Cron (recurring)</Radio.Button>
            <Radio.Button value="one-shot">One-shot</Radio.Button>
          </Radio.Group>
        </Form.Item>

        {triggerKind === 'cron' && (
          <>
            <Form.Item
              label="Cron expression"
              name="cronExpr"
              rules={[{ required: true, message: 'Cron expression is required' }]}
              extra="Spring 6-field cron format: sec min hour day month weekday — e.g. 0 0 9 * * MON-FRI for 9am weekdays."
            >
              <Input placeholder="0 0 9 * * *" />
            </Form.Item>

            <Form.Item
              label="Timezone"
              name="timezone"
              rules={[{ required: true }]}
              extra="Cron is evaluated in this zone. Defaults to Asia/Shanghai."
            >
              <Select options={TIMEZONE_OPTIONS} showSearch optionFilterProp="label" />
            </Form.Item>
          </>
        )}

        {triggerKind === 'one-shot' && (
          <>
            <Form.Item
              label="Trigger at"
              name="oneShotAt"
              rules={[{ required: true, message: 'Pick a date and time' }]}
            >
              <DatePicker
                showTime
                style={{ width: '100%' }}
                format="YYYY-MM-DD HH:mm:ss"
                disabledDate={(d) => d.isBefore(dayjs().startOf('day'))}
              />
            </Form.Item>
            <Form.Item label="Timezone" name="timezone" rules={[{ required: true }]}>
              <Select options={TIMEZONE_OPTIONS} showSearch optionFilterProp="label" />
            </Form.Item>
          </>
        )}

        {isEdit && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message={
              <span>
                Next fire: <code>{nextFireDisplay}</code>
              </span>
            }
            description="Backend computes this from the cron expression / one-shot time after each save."
          />
        )}

        <Divider style={{ margin: '8px 0 16px' }} />

        <Form.Item
          label="Prompt template"
          name="promptTemplate"
          rules={[{ required: true, message: 'Prompt is required' }]}
          extra="Sent verbatim as the user message when the task fires (no variable substitution in MVP)."
        >
          <TextArea rows={6} placeholder="e.g. 总结今天的会议笔记并发送到飞书…" />
        </Form.Item>

        <Form.Item
          label="Session mode"
          name="sessionMode"
          rules={[{ required: true }]}
        >
          <Radio.Group>
            <Radio.Button value="new">New session each fire</Radio.Button>
            <Radio.Button value="reuse">Reuse one session</Radio.Button>
          </Radio.Group>
        </Form.Item>

        <Divider style={{ margin: '8px 0 16px' }} />

        <Form.Item label="Channel push" name="channelType">
          <Select
            options={CHANNEL_TYPE_OPTIONS}
            onChange={(v) => setChannelType(v as string)}
          />
        </Form.Item>

        {channelType && (
          <Form.Item
            label="Channel id"
            name="channelId"
            rules={[{ required: true, message: 'Channel id is required when push is on' }]}
            extra={
              channelType === 'feishu'
                ? 'Feishu open_chat_id (e.g. oc_xxxx)'
                : 'Platform-specific recipient id'
            }
          >
            <Input placeholder={channelType === 'feishu' ? 'oc_xxxxxxxx' : 'recipient id'} />
          </Form.Item>
        )}

        <Form.Item label="Enabled" name="enabled" valuePropName="checked">
          <Switch />
        </Form.Item>
      </Form>
    </Drawer>
  );
};

export default ScheduleEditDrawer;
