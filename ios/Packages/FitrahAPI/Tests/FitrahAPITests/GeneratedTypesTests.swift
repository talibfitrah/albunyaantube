import Foundation
import Testing
@testable import FitrahAPI

@Suite(.perTest)
struct GeneratedTypesTests {
    @Test func decodesCategoryDto() throws {
        let json = Data(#"{"id":"cat1","name":"Quran","slug":"quran","parentId":null}"#.utf8)
        let dto = try JSONDecoder().decode(Components.Schemas.CategoryDto.self, from: json)
        #expect(dto.id == "cat1")
        #expect(dto.name == "Quran")
        #expect(dto.slug == "quran")
        #expect(dto.parentId == nil)
    }

    @Test func decodesContentItemDtoWithNullableFields() throws {
        let json = Data(#"{"id":"v1","type":"VIDEO","title":"Lecture","durationSeconds":600,"viewCount":null}"#.utf8)
        let dto = try JSONDecoder().decode(Components.Schemas.ContentItemDto.self, from: json)
        #expect(dto.id == "v1")
        #expect(dto._type == .video)
        #expect(dto.title == "Lecture")
        #expect(dto.durationSeconds == 600)
        #expect(dto.viewCount == nil)
    }
}
