"""
Rummikub tile-detection server.

Runs the YOLOv11m model (best.pt, trained in Colab on Roboflow-labeled data)
locally and exposes one endpoint that mirrors what Roboflow's serverless API
used to return - but in our own, simpler JSON shape - so the Android client
only needs to change which URL it calls, not how it parses the answer.

Run with:
    uvicorn server:app --host 0.0.0.0 --port 8001
"""

import base64
import io

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from PIL import Image
from ultralytics import YOLO

app = FastAPI()

# Loaded once, when the process starts - not on every request. Loading a
# ~40MB PyTorch model takes real time (disk read + building the network in
# memory), so doing it once and reusing it is what makes each individual
# request fast (roughly just the actual inference time, nothing else).
model = YOLO("best.pt")


class DetectRequest(BaseModel):
    image: str  # base64-encoded JPEG, no "data:" prefix - matches what the
                # Android client already produces (Base64.encodeToString)


@app.post("/detect")
def detect(req: DetectRequest):
    try:
        image_bytes = base64.b64decode(req.image)
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"invalid image: {e}")

    width, height = image.size

    # One call does preprocessing (resize to 640x640, normalize colors) +
    # the actual forward pass through the network + NMS (dropping duplicate/
    # overlapping boxes) - the exact steps Roboflow's serverless API used to
    # run for us, now running locally instead.
    results = model(image, verbose=False)[0]

    predictions = []
    for box in results.boxes:
        class_id = int(box.cls[0])
        class_name = model.names[class_id]          # e.g. "R7", "Joker" - the
                                                      # model was trained with
                                                      # these exact class names
        confidence = float(box.conf[0])

        # Ultralytics gives xyxy = top-left + bottom-right corners, in PIXEL
        # coordinates relative to the ORIGINAL image size (it rescales back
        # automatically after running at 640x640 internally). That's already
        # the corner format our Android BoundingBox wants - unlike Roboflow's
        # center-based format, no center-to-corner math is needed here.
        x1, y1, x2, y2 = box.xyxy[0].tolist()

        predictions.append({
            "class": class_name,
            "confidence": confidence,
            "x": x1 / width,
            "y": y1 / height,
            "width": (x2 - x1) / width,
            "height": (y2 - y1) / height,
        })

    return {
        "predictions": predictions,
        "image": {"width": width, "height": height},
    }


@app.get("/health")
def health():
    # quick way to check the server + model are up, without sending an image
    return {"status": "ok", "classes": len(model.names)}
