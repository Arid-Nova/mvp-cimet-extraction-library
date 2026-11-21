const express = require('express');
const axios = require('axios');
const app = express();
class UserService extends BaseService {
  name;
  async load(id) { return axios.get('/remote/' + id); }
}
class AuditRepository { value; save(event) { return event; } }
function user(id) { return fetch('/users/' + id); }
app.get('/users/:id', user);
server.addService(proto.UsersService.service, implementation);
new proto.UsersClient('local', credentials);
