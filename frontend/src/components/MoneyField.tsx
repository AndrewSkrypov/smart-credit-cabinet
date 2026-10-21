import { InputAdornment, TextField, TextFieldProps } from '@mui/material';

const grouping = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 });

type MoneyFieldProps = Omit<TextFieldProps, 'value' | 'onChange' | 'type'> & {
  value: number | null;
  onChange: (value: number | null) => void;
};

/** Rubles typed as digits and shown with thousand separators: «1 500 000 ₽». */
export function MoneyField({ value, onChange, ...props }: MoneyFieldProps) {
  return (
    <TextField
      {...props}
      inputMode="numeric"
      value={value == null ? '' : grouping.format(value)}
      onChange={(event) => {
        const digits = event.target.value.replace(/\D/g, '').slice(0, 12);
        onChange(digits ? Number(digits) : null);
      }}
      InputProps={{ endAdornment: <InputAdornment position="end">₽</InputAdornment>, ...props.InputProps }}
    />
  );
}
