import { Box, LinearProgress, Step, StepLabel, Stepper, Typography, useMediaQuery, useTheme } from '@mui/material';
import { stageIndex, stages } from '../../../shared/applicationText';
import { ApplicationStatus } from '../../../types';

/**
 * Where the application is on the path; a refusal marks the decision stage as failed. On a phone
 * seven steps do not fit side by side, so the stage is a line with a progress bar instead.
 */
export function StageStepper({ status }: { status: ApplicationStatus }) {
  const theme = useTheme();
  const narrow = useMediaQuery(theme.breakpoints.down('md'));
  const current = stageIndex(status);
  if (current < 0) return null;
  if (narrow) {
    const shown = Math.min(current, stages.length - 1);
    return (
      <Box>
        <Typography variant="body2" color="text.secondary">
          {current >= stages.length ? 'Все этапы пройдены' : `Этап ${shown + 1} из ${stages.length}`}
        </Typography>
        <Typography fontWeight={700} color={status === 'REJECTED' ? 'error' : 'text.primary'}>{stages[shown]}</Typography>
        <LinearProgress
          variant="determinate"
          value={(Math.min(current + 1, stages.length) / stages.length) * 100}
          color={status === 'REJECTED' ? 'error' : 'primary'}
          sx={{ mt: 1, height: 6, borderRadius: 99 }}
          aria-label="Ход рассмотрения"
        />
      </Box>
    );
  }
  return (
    <Stepper activeStep={current} alternativeLabel aria-label="Этапы заявки">
      {stages.map((label, index) => (
        <Step key={label} completed={index < current}>
          <StepLabel error={status === 'REJECTED' && index === current}>{label}</StepLabel>
        </Step>
      ))}
    </Stepper>
  );
}
