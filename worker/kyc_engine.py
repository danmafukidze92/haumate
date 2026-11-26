"""OCR, document parsing, face matching and liveness for Zimbabwe KYC documents.

Face detection/recognition uses OpenCV's YuNet + SFace models (downloaded in the Docker image).
The mobile app runs the interactive liveness challenge on-device (ML Kit: blink / turn head)
and uploads the frames; the server re-checks that frames show the same live face and real head movement.
"""
import os
import re
from datetime import datetime, date

import cv2
import numpy as np
import pytesseract

MODELS = os.getenv("MODELS_DIR", "/models")
_det = _rec = None


def _load(path):
    img = cv2.imread(path)
    if img is None:
        raise ValueError(f"cannot read image {os.path.basename(path)}")
    h, w = img.shape[:2]
    if max(h, w) > 1600:
        s = 1600 / max(h, w)
        img = cv2.resize(img, (int(w * s), int(h * s)))
    return img


def ocr(path):
    img = _load(path)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    gray = cv2.bilateralFilter(gray, 9, 75, 75)
    th = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 31, 11)
    text = pytesseract.image_to_string(th, config="--oem 3 --psm 6")
    if len(text.strip()) < 20:  # some cards read better unthresholded
        text = pytesseract.image_to_string(gray, config="--oem 3 --psm 6")
    return text


DATE_RE = re.compile(r"(\d{1,2})[/\-. ](\d{1,2}|[A-Z]{3})[/\-. ](\d{2,4})", re.I)
MONTHS = {m: i for i, m in enumerate(["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"], 1)}


def _dates(text):
    out = []
    for d, m, y in DATE_RE.findall(text):
        try:
            mm = MONTHS.get(m.upper()) if not m.isdigit() else int(m)
            yy = int(y) + (2000 if len(y) == 2 and int(y) < 50 else 1900 if len(y) == 2 else 0)
            out.append(date(yy, mm, int(d)))
        except (ValueError, TypeError):
            pass
    return out


def _mrz_date(s):
    try:
        y = int(s[:2]); y += 2000 if y < 50 else 1900
        return date(y, int(s[2:4]), int(s[4:6]))
    except ValueError:
        return None


def parse(doc_type, text):
    t = text.upper()
    f = {}
    if doc_type == "NATIONAL_ID":
        m = re.search(r"\b(\d{2})\s*[-–]?\s*(\d{6,7})\s*[-–]?\s*([A-Z])\s*[-–]?\s*(\d{2})\b", t)
        if m:
            f["id_number"] = f"{m.group(1)}-{m.group(2)}{m.group(3)}{m.group(4)}"
        ds = _dates(t)
        if ds:
            f["date_of_birth"] = min(ds).isoformat()
    elif doc_type == "PASSPORT":
        lines = [l.replace(" ", "") for l in t.splitlines() if "<<" in l]
        if len(lines) >= 2:
            l2 = lines[-1]
            f["passport_number"] = l2[:9].replace("<", "")
            f["nationality"] = l2[10:13]
            dob, exp = _mrz_date(l2[13:19]), _mrz_date(l2[21:27])
            if dob: f["date_of_birth"] = dob.isoformat()
            if exp: f["expiry"] = exp.isoformat()
            nm = lines[-2][5:].split("<<", 1)
            f["surname"] = nm[0].replace("<", " ").strip()
            if len(nm) > 1:
                f["given_names"] = nm[1].replace("<", " ").strip()
    elif doc_type == "DRIVERS_LICENCE":
        m = re.search(r"\b(\d{5,8}[A-Z]{0,2})\b", t)
        if m:
            f["licence_number"] = m.group(1)
        m = re.search(r"CLASS\W*([1-5](?:\s*,\s*[1-5])*)", t)
        if m:
            f["licence_class"] = m.group(1).replace(" ", "")
        ds = _dates(t)
        if ds:
            f["expiry"] = max(ds).isoformat()
    elif doc_type in ("VEHICLE_REG", "ZINARA_DISC", "INSURANCE"):
        m = re.search(r"\b([A-Z]{3}\s?\d{4})\b", t)  # Zimbabwe plates e.g. ABC 1234
        if m:
            f["reg_number"] = m.group(1).replace(" ", "")
        ds = _dates(t)
        if ds:
            f["expiry"] = max(ds).isoformat()
    return f


def checks(doc_type, f, full_name):
    out = []
    today = date.today()
    if doc_type == "NATIONAL_ID":
        out.append("OK: ID number read" if f.get("id_number") else "FAIL: ID number not readable, retake photo")
    if doc_type == "PASSPORT":
        out.append("OK: MRZ read" if f.get("passport_number") else "FAIL: passport MRZ not readable")
        if f.get("surname") and full_name and f["surname"].split()[0].lower() not in full_name.lower():
            out.append("FAIL: passport name does not match account name")
    if f.get("expiry"):
        exp = date.fromisoformat(f["expiry"])
        out.append("OK: valid until " + f["expiry"] if exp >= today else "FAIL: document expired " + f["expiry"])
    if f.get("date_of_birth"):
        age = (today - date.fromisoformat(f["date_of_birth"])).days // 365
        if age < 18:
            out.append("FAIL: holder appears under 18")
    if doc_type == "DRIVERS_LICENCE" and not f.get("licence_number"):
        out.append("FAIL: licence number not readable")
    return out


def _models():
    global _det, _rec
    if _det is None:
        det = os.path.join(MODELS, "face_detection_yunet_2023mar.onnx")
        rec = os.path.join(MODELS, "face_recognition_sface_2021dec.onnx")
        if not (os.path.exists(det) and os.path.exists(rec)):
            raise RuntimeError("face models missing in " + MODELS)
        _det = cv2.FaceDetectorYN.create(det, "", (320, 320), 0.8)
        _rec = cv2.FaceRecognizerSF.create(rec, "")
    return _det, _rec


def _face(img):
    det, _ = _models()
    det.setInputSize((img.shape[1], img.shape[0]))
    _, faces = det.detect(img)
    if faces is None or len(faces) == 0:
        return None
    return max(faces, key=lambda f: f[2] * f[3])  # largest face


def _embed(img, face):
    _, rec = _models()
    return rec.feature(rec.alignCrop(img, face))


def face_match(doc_path, selfie_path):
    a, b = _load(doc_path), _load(selfie_path)
    fa, fb = _face(a), _face(b)
    if fa is None or fb is None:
        return None
    _, rec = _models()
    return float(rec.match(_embed(a, fa), _embed(b, fb), cv2.FaceRecognizerSF_FR_COSINE))


def liveness(selfie_path, frames):
    """Frames must show the same person as the selfie, and the head/eyes must actually move
    in the direction of the challenge (a static photo held up to the camera fails)."""
    if len(frames) < 2:
        return {"passed": False, "reason": "needs at least 2 challenge frames"}
    s = _load(selfie_path)
    fs = _face(s)
    if fs is None:
        return {"passed": False, "reason": "no face in selfie"}
    _, rec = _models()
    ref = _embed(s, fs)
    yaws = []
    for path, challenge in frames:
        img = _load(path)
        f = _face(img)
        if f is None:
            return {"passed": False, "reason": "face lost during challenge"}
        if rec.match(ref, _embed(img, f), cv2.FaceRecognizerSF_FR_COSINE) < 0.30:
            return {"passed": False, "reason": "different person in challenge frames"}
        # landmarks: right eye(4,5) left eye(6,7) nose(8,9)
        eye_mid = (f[4] + f[6]) / 2
        yaws.append((f[8] - eye_mid) / max(f[2], 1))
    spread = max(yaws) - min(yaws)
    if spread < 0.06:
        return {"passed": False, "reason": f"no head movement detected ({spread:.2f})"}
    return {"passed": True, "reason": f"movement {spread:.2f}"}
