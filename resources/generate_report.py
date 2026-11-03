import json
import os
import sys
from xml.sax.saxutils import escape
from reportlab.lib.pagesizes import letter
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak
from reportlab.lib.styles import getSampleStyleSheet

def load_json(path):
    if not os.path.exists(path):
        return None
    try:
        with open(path, "r") as f:
            return json.load(f)
    except Exception as e:
        print(f"Failed to parse {path}: {e}", file=sys.stderr)
        return None

def generate_pdf():
    pdf_filename = "security-report.pdf"
    doc = SimpleDocTemplate(pdf_filename, pagesize=letter)
    styles = getSampleStyleSheet()
    story = []

    story.append(Paragraph("<b>Automated Security Scan Report</b>", styles['Title']))
    story.append(Spacer(1, 20))

    # --- SEMGREP SECTION ---
    story.append(Paragraph("<b>1. Static Analysis (Semgrep)</b>", styles['Heading1']))
    
    semgrep_data = load_json("semgrep-report.json")
    if semgrep_data:
        findings = semgrep_data.get("results", [])
        if not findings:
            story.append(Paragraph("✅ No issues found by Semgrep.", styles['Normal']))
        else:
            story.append(Paragraph(f"⚠️ Found {len(findings)} issues:", styles['Normal']))
            story.append(Spacer(1, 10))
            for idx, issue in enumerate(findings, 1):
                msg = escape(issue.get("extra", {}).get("message", "No description"))
                path = escape(issue.get("path", "unknown"))
                line = issue.get("start", {}).get("line", "?")
                check_id = escape(issue.get('check_id', 'unknown-rule'))
                
                text = f"<b>{idx}. {check_id}</b><br/>File: {path} (Line {line})<br/>Issue: {msg}"
                story.append(Paragraph(text, styles['Normal']))
                story.append(Spacer(1, 10))
    else:
        story.append(Paragraph("Semgrep report not found or malformed.", styles['Normal']))
        
    story.append(PageBreak())

    # --- ZAP SECTION ---
    story.append(Paragraph("<b>2. Dynamic Analysis (OWASP ZAP)</b>", styles['Heading1']))
    
    zap_data = load_json("zap-report.json")
    if zap_data:
        sites = zap_data.get("site", [])
        for site in sites:
            story.append(Paragraph(f"<b>Target:</b> {escape(site.get('@name', 'Unknown'))}", styles['Heading2']))
            alerts = site.get("alerts", [])
            
            if not alerts:
                story.append(Paragraph("✅ No alerts found by ZAP.", styles['Normal']))
            else:
                for alert in alerts:
                    name = escape(alert.get("alert", "Unknown Alert"))
                    risk = escape(alert.get("riskdesc", "Unknown Risk"))
                    desc = escape(alert.get("desc", "").replace("\n", " ")[:200]) + "..."
                    count = len(alert.get("instances", []))
                    
                    text = f"<b>{name}</b><br/>Risk: {risk}<br/>Instances: {count}<br/>Description: {desc}"
                    story.append(Paragraph(text, styles['Normal']))
                    story.append(Spacer(1, 10))
    else:
        story.append(Paragraph("ZAP report not found or malformed.", styles['Normal']))

    doc.build(story)
    print(f"Generated {pdf_filename}")

if __name__ == "__main__":
    generate_pdf()
