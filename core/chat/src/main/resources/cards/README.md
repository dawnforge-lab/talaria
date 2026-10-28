# Rich card schema

`card-schema.json` is the shared definition of the push plugin's rich cards (`talaria_show`),
copied from the Talaria plugin (`cards/`, schema version 3) under the MIT licence in
`LICENSE-card-schema.txt`. The plugin's contact cards also take a `message` to text, which the app
doesn't draw yet, so its copy leaves that field out. The plugin and the phone normalize cards by
it; `core/chat/src/test/resources/cards/card-vectors.json` (same source) checks they agree.
Copy both again when the plugin's schema version changes.
