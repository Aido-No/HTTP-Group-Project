The following is a list of commands to test every function of the project. They should be done in order.

## Showing POST
POST `http://localhost:3000/memes`
`{"name":"Funny Dog","category":"meme","SetUp":"A Dog with a funny Face", "Punchline":"A Dog with a Funny face"}`
## Showing GET (all)
GET `http://localhost:3000/resource`
## GETing the meme you made
GET `http://localhost:3000/resource/5`
## Showing GET creating Cookies
Click `Show Cookies` Button
## Showing Delete Cookies works
- Click `Clear Cookies` Button
- Click `Show Cookies` Buttton
## Showing Etag Works
GET `http://localhost:3000/resource/5`
## Showing CRUD Works 
- POST `http://localhost:3000/resource/5/comments`
`{"author":"JohnDoe","text":"This is hilarious!"}`
- GET `http://localhost:3000/resource/5/comments`
- PUT `http://localhost:3000/resource/5/comments/1`
`{"author":"JohnDoe","text":"This is hilarious! I think..."}`
- DELETE `http://localhost:3000/resource/5/comments/1`
- GET `http://localhost:3000/resource/5/comments/count`
## Showing GET ALL Works (again)
GET `http://localhost:3000/resource`
## Showing PUT Works
- PUT `http://localhost:3000/memes/5`
`{"name":"Funny Cat","category":"meme","SetUp":"A Cat with a funny Face", "Punchline":"A Cat with a Funny face"}`
- GET `http://localhost:3000/resource/5`
## Showing Delete Works
- DELETE `http://localhost:3000/resource/5`
- GET `http://localhost:3000/resource/5`
- GET `http://localhost:3000/resource`
## Showing Log Works
Open `project.log` in the root folder
## Showing the static HTML endpoint works
GET `http://localhost:3000/`
