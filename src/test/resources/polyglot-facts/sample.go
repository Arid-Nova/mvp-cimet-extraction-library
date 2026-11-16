package main
import (
  "net/http"
  pb "example/pb"
)
type UserService struct { Name string }
type AuditRepository struct { Count int }
func (s *UserService) Load(id string) { http.Get("/remote/" + id) }
func routes() {
  router.GET("/users/:id", handle)
  http.HandleFunc("/health", health)
  pb.RegisterUsersServer(server, handler)
  client := pb.NewUsersClient(connection)
}
