import json
import os
from reportlab.lib.pagesizes import letter
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib import colors

def generate_pdf():
    pdf_filename = "security-report.pdf"
    doc = SimpleDocTemplate(pdf_filename, pagesize=letter)
    styles = getSampleStyleSheet()
    story = []

    # Title
    story.append(Paragraph("<b>Automated Security Scan Report</b>", styles['Title']))
    story.append(Spacer(1, 20))

    # --- SEMGREP SECTION ---
    story.append(Paragraph("<b>1. Static Analysis (Semgrep)</b>", styles['Heading1']))
    
    if os.path.exists("semgrep-report.json"):
        with open("semgrep-report.json", "r") as f:
            semgrep_data = json.load(f)
            
        findings = semgrep_data.get("results", [])
        if not findings:
            story.append(Paragraph("✅ No issues found by Semgrep.", styles['Normal']))
        else:
            story.append(Paragraph(f"⚠️ Found {len(findings)} issues:", styles['Normal']))
            story.append(Spacer(1, 10))
            for idx, issue in enumerate(findings, 1):
                msg = issue.get("extra", {}).get("message", "No description")
                path = issue.get("path", "unknown")
                line = issue.get("start", {}).get("line", "?")
                text = f"<b>{idx}. {issue.get('check_id')}</b><br/>File: {path} (Line {line})<br/>Issue: {msg}"
                story.append(Paragraph(text, styles['Normal']))
                story.append(Spacer(1, 10))
    else:
        story.append(Paragraph("Semgrep report not found.", styles['Normal']))
        
    story.append(PageBreak())

    # --- ZAP SECTION ---
    story.append(Paragraph("<b>2. Dynamic Analysis (OWASP ZAP)</b>", styles['Heading1']))
    
    if os.path.exists("zap-report.json"):
        with open("zap-report.json", "r") as f:
            zap_data = json.load(f)
            
        sites = zap_data.get("site", [])
        for site in sites:
            story.append(Paragraph(f"<b>Target:</b> {site.get('@name')}", styles['Heading2']))
            alerts = site.get("alerts", [])
            
            if not alerts:
                story.append(Paragraph("✅ No alerts found by ZAP.", styles['Normal']))
            else:
                for alert in alerts:
                    name = alert.get("alert")
                    risk = alert.get("riskdesc")
                    desc = alert.get("desc", "").replace("\n", " ")[:200] + "..."
                    count = len(alert.get("instances", []))
                    
                    text = f"<b>{name}</b><br/>Risk: {risk}<br/>Instances: {count}<br/>Description: {desc}"
                    story.append(Paragraph(text, styles['Normal']))
                    story.append(Spacer(1, 10))
    else:
        story.append(Paragraph("ZAP report not found.", styles['Normal']))

    doc.build(story)
    print(f"Generated {pdf_filename}")

if __name__ == "__main__":
    generate_pdf()
