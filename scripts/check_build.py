#!/usr/bin/env python3
"""Monitor GitHub Actions build status for termux-api-extended."""
import subprocess, sys, time, json

REPO = "sanyagouan/termux-api-extended"

def gh_json(args):
    r = subprocess.run(["gh"] + args, capture_output=True, text=True, cwd="/data/data/com.termux/files/home/projects/termux-api-extended")
    if r.returncode != 0:
        print(f"gh error: {r.stderr}", file=sys.stderr)
        return None
    return json.loads(r.stdout)

def get_latest_run():
    runs = gh_json(["run", "list", "--repo", REPO, "--limit", "1", "--json", "databaseId,status,conclusion,headBranch,displayTitle"])
    if not runs:
        return None
    return runs[0]

def main():
    if len(sys.argv) > 1:
        run_id = sys.argv[1]
    else:
        run = get_latest_run()
        if not run:
            print("No runs found")
            sys.exit(2)
        run_id = str(run["databaseId"])
        print(f"Latest run: {run_id} - {run.get('displayTitle', 'N/A')}")

    print(f"Monitoring run {run_id}...")
    while True:
        data = gh_json(["run", "view", run_id, "--repo", REPO, "--json", "status,conclusion"])
        if not data:
            sys.exit(2)
        status = data["status"]
        conclusion = data.get("conclusion")
        print(f"  [{time.strftime('%H:%M:%S')}] status={status} conclusion={conclusion}")
        if status == "completed":
            if conclusion == "success":
                print("✅ BUILD SUCCEEDED")
                sys.exit(0)
            else:
                print(f"❌ BUILD FAILED: {conclusion}")
                # Fetch error log
                r = subprocess.run(["gh", "run", "view", run_id, "--repo", REPO, "--log-failed"],
                                   capture_output=True, text=True,
                                   cwd="/data/data/com.termux/files/home/projects/termux-api-extended")
                if r.stdout:
                    lines = r.stdout.strip().split("\n")
                    print("\n--- LAST 80 ERROR LINES ---")
                    for line in lines[-80:]:
                        print(line)
                sys.exit(1)
        time.sleep(30)

if __name__ == "__main__":
    main()
