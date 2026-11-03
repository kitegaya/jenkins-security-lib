import json
import os
import sys
import datetime
from xml.sax.saxutils import escape
from reportlab.lib.pagesizes import letter
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.lib import colors

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

    # --- METADATA ---
    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S UTC")
    story.append(Paragraph("<b>Automated Security Scan Report</b>", styles['Title']))
    story.append(Paragraph(f"<b>Generated:</b> {now}", styles['Normal']))
    story.append(Spacer(1, 20))

    # --- EXECUTIVE SUMMARY ---
    story.append(Paragraph("<b>Executive Summary</b>", styles['Heading1']))
    
    semgrep_data = load_json("semgrep-report.json")
    zap_data = load_json("zap-report.json")
    
    semgrep_count = len(semgrep_data.get("results", [])) if semgrep_data else 0
    zap_count = 0
    if zap_data:
        for site in zap_data.get("site", []):
            zap_count += len(site.get("alerts", []))

    story.append(Paragraph(f"• <b>Semgrep Findings:</b> {semgrep_count}", styles['Normal']))
    story.append(Paragraph(f"• <b>OWASP ZAP Alerts:</b> {zap_count}", styles['Normal']))
    story.append(Spacer(1, 15))
    story.append(PageBreak())

    # --- SEMGREP SECTION ---
    story.append(Paragraph("<b>1. Static Analysis (Semgrep)</b>", styles['Heading1']))
    
    if semgrep_data:
        findings = semgrep_data.get("results", [])
        if not findings:
            story.append(Paragraph("✅ No issues found by Semgrep.", styles['Normal']))
        else:
            story.append(Paragraph(f"⚠️ Found {len(findings)} issues:", styles['Normal']))
            story.append(Spacer(1, 10))
            
            for idx, issue in enumerate(findings, 1):
                msg = escape(str(issue.get("extra", {}).get("message", "No description")))
                path = escape(str(issue.get("path", "unknown")))
                line = issue.get("start", {}).get("line", "?")
                check_id = escape(str(issue.get('check_id', 'unknown-rule')))
                severity = str(issue.get("extra", {}).get("severity", "INFO")).upper()
                
                # Color code severity
                color = colors.black
                if severity == "ERROR":
                    color = colors.red
                elif severity == "WARNING":
                    color = colors.orange
                    
                text = f"<b>{idx}. {check_id}</b> <font color='{color.hexval()}'><b>[{severity}]</b></font><br/>File: {path} (Line {line})<br/>Issue: {msg}"
                story.append(Paragraph(text, styles['Normal']))
                story.append(Spacer(1, 10))
    else:
        story.append(Paragraph("Semgrep report not found or malformed.", styles['Normal']))
        
    story.append(PageBreak())

    # --- ZAP SECTION ---
    story.append(Paragraph("<b>2. Dynamic Analysis (OWASP ZAP)</b>", styles['Heading1']))
    
    if zap_data:
        sites = zap_data.get("site", [])
        for site in sites:
            story.append(Paragraph(f"<b>Target:</b> {escape(str(site.get('@name', 'Unknown')))}", styles['Heading2']))
            alerts = site.get("alerts", [])
            
            if not alerts:
                story.append(Paragraph("✅ No alerts found by ZAP.", styles['Normal']))
            else:
                for alert in alerts:
                    name = escape(str(alert.get("alert", "Unknown Alert")))
                    risk = str(alert.get("riskdesc", "Unknown Risk"))
                    
                    # Clean and truncate description safely
                    raw_desc = str(alert.get("desc", ""))
                    clean_desc = raw_desc.replace("\n", " ").replace("\r", " ")
                    if len(clean_desc) > 300:
                        clean_desc = clean_desc[:300] + "..."
                    desc = escape(clean_desc)
                    
                    count = len(alert.get("instances", []))
                    
                    # Color code risk
                    color = colors.black
                    if "High" in risk:
                        color = colors.red
                    elif "Medium" in risk:
                        color = colors.orange
                    elif "Low" in risk:
                        color = colors.blue
                        
                    text = f"<b>{name}</b> <font color='{color.hexval()}'><b>[{risk}]</b></font><br/>Instances: {count}<br/>Description: {desc}"
                    story.append(Paragraph(text, styles['Normal']))
                    story.append(Spacer(1, 10))
    else:
        story.append(Paragraph("ZAP report not found or malformed.", styles['Normal']))

    doc.build(story)
    print(f"Generated {pdf_filename}")

if __name__ == "__main__":
    generate_pdf()
