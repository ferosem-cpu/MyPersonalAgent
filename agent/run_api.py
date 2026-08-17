import uvicorn

from api.server import create_app

app = create_app()

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=7011)  # 7011: web_ui already owns 5000
