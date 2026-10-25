import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { MoneyField } from './MoneyField';

describe('money input', () => {
  it('places numeric mode and merged caller attributes on the real input, retaining digit formatting and clearing', () => {
    function Form() {
      const [value, setValue] = useState<number | null>(1_500_000);
      return <MoneyField label="Сумма" value={value} onChange={setValue} inputProps={{ 'aria-describedby': 'money-help', maxLength: 20 }} />;
    }
    render(<Form />);
    const input = screen.getByRole('textbox', { name: 'Сумма' });
    expect(input).toHaveAttribute('inputmode', 'numeric');
    expect(input).toHaveAttribute('aria-describedby', 'money-help'); expect(input).toHaveAttribute('maxlength', '20');
    expect(input.parentElement).not.toHaveAttribute('inputmode'); expect(input).not.toHaveAttribute('type', 'number');
    fireEvent.change(input, { target: { value: '2 345 678 ₽' } }); expect(input).toHaveValue('2\u00a0345\u00a0678');
    fireEvent.change(input, { target: { value: '' } }); expect(input).toHaveValue('');
  });
});
