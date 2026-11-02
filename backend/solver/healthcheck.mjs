// Run every 10 s by fitrah-solver-watchdog.service (S20), which SIGKILLs the solver on exit 2 so Restart=on-failure applies.
// The vm timeout is not a hard bound: Node's watchdog calls TerminateExecution once, and if that lands while V8 runs Node's
// host promise-reject callback (a realm's unhandled rejection, or import()'s host callback), Node's TryCatchScope catches it,
// V8's ~TryCatch rethrows it, and Runtime_PromiseRejectEventFromStack returns undefined: lost, so the realm runs untimed and a
// for(;;) after it hangs the solver for good. A blocked process cannot rescue itself, and an in-process watchdog thread needs
// --allow-worker, which lets host code start a Worker with execArgv: [] outside the permission model (R40). So: from outside.
// Exit 2 only when the solver accepted the connection but never answered. Refused (stopped, or in RestartSec) and any HTTP
// status, a warming 503 included, exit 0: systemd's own restart logic owns those.
const port = process.env.SOLVER_PORT || 4417;
try {
  await fetch(`http://127.0.0.1:${port}/health`, { signal: AbortSignal.timeout(20_000) });   // a real build blocks ~1 s
} catch (error) {
  if (error.name !== 'TimeoutError') process.exit(0);
  console.error(`FATAL solver on port ${port} accepted but did not answer /health within 20 s; its event loop is blocked`);
  process.exit(2);
}
