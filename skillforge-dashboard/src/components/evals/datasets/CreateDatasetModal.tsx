import { Modal, Form, Input, Select, message } from 'antd';
import { useMutation } from '@tanstack/react-query';
import { createDataset, type EvalDataset } from '../../../api/evalDataset';

interface CreateDatasetModalProps {
  open: boolean;
  ownerId: number;
  /** Optional list of agents (id + name) to populate the agentId selector. */
  agents?: Array<{ id: string | number; name?: string | null }>;
  onClose: () => void;
  onCreated: (dataset: EvalDataset) => void;
}

interface FormValues {
  name: string;
  description?: string;
  agentId?: string;
  tags?: string[];
}

/**
 * EVAL-DATASET-LAYER V1 §5.2 — create new EvalDataset.
 *
 * Surfaces only the V1 user-editable fields; ownerId is injected from
 * AuthContext (V1 single-tenant). isPublic stays false per PRD scope.
 */
function CreateDatasetModal({ open, ownerId, agents = [], onClose, onCreated }: CreateDatasetModalProps) {
  const [form] = Form.useForm<FormValues>();

  const mutation = useMutation({
    mutationFn: (values: FormValues) =>
      createDataset({
        name: values.name.trim(),
        description: values.description?.trim() || undefined,
        ownerId,
        agentId: values.agentId || null,
        tags: values.tags && values.tags.length > 0 ? values.tags : undefined,
        isPublic: false,
      }).then((r) => r.data),
    onSuccess: (dataset) => {
      message.success(`Dataset "${dataset.name}" created.`);
      form.resetFields();
      onCreated(dataset);
    },
    onError: (err: unknown) => {
      const msg =
        err && typeof err === 'object' && 'response' in err
          ? (err as { response?: { data?: { message?: string } } }).response?.data?.message
          : null;
      message.error(msg || 'Failed to create dataset.');
    },
  });

  const handleOk = () => {
    form
      .validateFields()
      .then((values) => mutation.mutate(values))
      .catch(() => { /* validation error already surfaced inline */ });
  };

  return (
    <Modal
      open={open}
      title="Create Dataset"
      onCancel={() => {
        if (!mutation.isPending) {
          form.resetFields();
          onClose();
        }
      }}
      onOk={handleOk}
      okText="Create"
      okButtonProps={{ loading: mutation.isPending }}
      cancelButtonProps={{ disabled: mutation.isPending }}
      destroyOnHidden
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{ tags: [] }}
        onFinish={(values) => mutation.mutate(values)}
      >
        <Form.Item
          name="name"
          label="Name"
          rules={[
            { required: true, message: 'Name is required' },
            { max: 128, message: 'Max 128 chars' },
          ]}
        >
          <Input placeholder="e.g. main-assistant-baseline-v1" autoFocus />
        </Form.Item>

        <Form.Item name="description" label="Description">
          <Input.TextArea rows={3} placeholder="What this dataset is for." />
        </Form.Item>

        <Form.Item
          name="agentId"
          label="Target Agent"
          tooltip="Leave empty for a cross-agent / generic dataset."
        >
          <Select
            placeholder="(none — generic dataset)"
            allowClear
            options={agents.map((a) => ({
              value: String(a.id),
              label: a.name ? `${a.name} (#${a.id})` : `Agent #${a.id}`,
            }))}
          />
        </Form.Item>

        <Form.Item
          name="tags"
          label="Tags"
          tooltip='Free-form labels like "gaia", "lv1", "baseline".'
        >
          <Select
            mode="tags"
            placeholder="Add tags…"
            tokenSeparators={[',']}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}

export default CreateDatasetModal;
