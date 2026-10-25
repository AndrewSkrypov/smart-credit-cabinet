import { Box, Stack, Typography } from '@mui/material';
import React from 'react';
import { usePageTitle } from '../hooks/usePageTitle';

interface PageHeaderProps {
  title: string;
  subtitle?: string;
  actions?: React.ReactNode;
}

export function PageHeader({ title, subtitle, actions }: PageHeaderProps) {
  usePageTitle(title);
  return (
    <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" alignItems={{ xs: 'stretch', md: 'flex-start' }} spacing={2} sx={{ mb: 3 }}>
      <Box sx={{ minWidth: 0, overflowWrap: 'anywhere' }}>
        <Typography variant="h4" component="h1" fontWeight={700} sx={{ fontSize: { xs: '1.75rem', md: '2.5rem' } }}>{title}</Typography>
        {subtitle && <Typography color="text.secondary" sx={{ mt: 0.5 }}>{subtitle}</Typography>}
      </Box>
      {actions}
    </Stack>
  );
}
