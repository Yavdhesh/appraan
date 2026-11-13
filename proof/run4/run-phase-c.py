import subprocess, signal, sys, time
# Launcher that resets SIGINT to the default disposition in the child —
# the exact equivalent of a user starting Appraan in an interactive
# terminal (where Ctrl+C is later pressed). A plain `cmd &` from a
# non-interactive script inherits SIGINT ignored, and HotSpot then
# (correctly) leaves it ignored.
def reset_sigint():
    signal.signal(signal.SIGINT, signal.SIG_DFL)
p = subprocess.Popen(sys.argv[1:], preexec_fn=reset_sigint)
print(f"APPRAAN_PID={p.pid}", flush=True)
sys.exit(p.wait())
