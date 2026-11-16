using System.Net.Http;
[ApiController]
[Route("users")]
public class UsersController : ControllerBase {
    public string Name { get; set; }
    [HttpGet("{id}")]
    public string Get(string id) { client.GetAsync("/remote/" + id); return id; }
}
public class OtherService : Users.UsersBase {
    public void Run(string message) { client.PostAsync("/events", message); }
}
app.MapPost("/orders", handler);
new Users.UsersClient(channel);
