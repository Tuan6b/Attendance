import os
import uvicorn

if __name__ == "__main__":
    port = int(os.getenv("PORT", "8002"))
    host = os.getenv("HOST", "0.0.0.0")
    reload = os.getenv("RELOAD", "false").lower() == "true"
    uvicorn.run("app.main:app", host=host, port=port, reload=reload)
