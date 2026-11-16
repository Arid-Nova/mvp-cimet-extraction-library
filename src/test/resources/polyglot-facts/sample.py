import requests
from fastapi import FastAPI
app = FastAPI()
class UserService:
    name: str
    def load(self, user_id: int):
        return requests.get(f"/remote/{user_id}")
class AuditRepository:
    count: int = 0
    def save(self, event):
        return event
@app.get("/users/{user_id}")
def user(user_id: int):
    return requests.get("/users/" + str(user_id))
add_UsersServicer_to_server(implementation, server)
stub = UsersStub(channel)
