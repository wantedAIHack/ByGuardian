import type { ButtonHTMLAttributes } from 'react';

type Variant = 'primary' | 'quiet' | 'plain';

const CLASS: Record<Variant, string> = {
  primary: 'btn',
  quiet: 'btn btn-quiet',
  plain: 'btn btn-plain',
};

export function Button(
  { variant = 'primary', className = '', ...rest }:
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant },
) {
  return <button type="button" className={`${CLASS[variant]} ${className}`} {...rest} />;
}
