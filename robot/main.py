# main.py
import uvicorn
from server import app

if __name__=="__main__":
    uvicorn.run(app=app, host="0.0.0.0", port=9080, reload=False, workers=1)
