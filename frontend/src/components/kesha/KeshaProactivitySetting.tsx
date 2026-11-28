import { Checkbox, FormControlLabel, Typography } from '@mui/material';
import { useId } from 'react';

interface KeshaProactivitySettingProps {
  proactive: boolean;
  onChange: (proactive: boolean) => void;
}

export function KeshaProactivitySetting({ proactive, onChange }: KeshaProactivitySettingProps) {
  const labelId = useId();
  const descriptionId = useId();
  return (
    <FormControlLabel
      sx={{ mx: 0, minHeight: 44 }}
      control={(
        <Checkbox
          size="small"
          checked={proactive}
          onChange={(_, checked) => onChange(checked)}
          inputProps={{ 'aria-labelledby': labelId, 'aria-describedby': descriptionId }}
        />
      )}
      label={(
        <>
          <Typography id={labelId} variant="body2" component="span" display="block">Кеша сам предлагает подсказки</Typography>
          <Typography id={descriptionId} variant="caption" component="span" color="text.secondary" display="block">
            Выключите, чтобы Кеша оставался у кнопки чата
          </Typography>
        </>
      )}
    />
  );
}
